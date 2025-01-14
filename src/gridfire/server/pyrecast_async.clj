;; [[file:../../../org/GridFire.org::gridfire.server.pyrecast-async][gridfire.server.pyrecast-async]]
(ns gridfire.server.pyrecast-async
  "For exposing GridFire through a socket server, making it act as a worker process behind a job queue,
  which sends notifications to the client as the handling progresses.

  This is an in-process (not distributed), singleton (its state is namespace-anchored, being held in Vars),
  single-threaded server, which implies some usage limitations (you can't have several servers in the same JVM,
  there's no resilience to JVM crashes, and you can't scale out to several worker processes behind the job queue.)"
  (:require [clojure.core.async           :refer [>!! alts!! chan thread]]
            [clojure.data.json            :as json]
            [clojure.edn                  :as edn]
            [clojure.java.io              :as io]
            [clojure.java.shell           :as sh]
            [clojure.pprint               :refer [pprint]]
            [clojure.spec.alpha           :as spec]
            [clojure.string               :as str]
            [gridfire.active-fire-watcher :as active-fire-watcher]
            [gridfire.conversion          :refer [convert-date-string camel->kebab kebab->camel]]
            [gridfire.core                :as gridfire]
            [gridfire.simple-sockets      :as sockets]
            [gridfire.spec.server         :as server-spec]
            [gridfire.utils.server        :refer [nil-on-error throw-message]]
            [triangulum.logging           :refer [log log-str set-log-path!]]
            [triangulum.utils             :refer [parse-as-sh-cmd]])
  (:import java.text.SimpleDateFormat
           java.util.Calendar
           java.util.TimeZone))

(set! *unchecked-math* :warn-on-boxed)

;;=============================================================================
;; Request/Response Functions
;;=============================================================================

(def date-from-format "yyyy-MM-dd HH:mm zzz")
(def date-to-format   "yyyyMMdd_HHmmss")

(defn- build-geosync-request [{:keys [fire-name ignition-time suppression] :as _request}
                              {:keys [geosync-data-dir host] :as _config}]
  (let [updated-fire-name (if suppression (str fire-name "-suppressed") fire-name)
        timestamp         (convert-date-string ignition-time date-from-format date-to-format)]
    (json/write-str
     {"action"             "add"
      "dataDir"            (format "%s/%s/%s" geosync-data-dir updated-fire-name timestamp)
      "geoserverWorkspace" (format "fire-spread-forecast_%s_%s" updated-fire-name timestamp)
      "responseHost"       host
      "responsePort"       5555})))

(defn- send-geosync-request! [request {:keys [geosync-host geosync-port] :as config}]
  (sockets/send-to-server! geosync-host
                           geosync-port
                           (build-geosync-request request config)))

(defn- build-gridfire-response [request {:keys [host port] :as _config} status status-msg]
  (json/write-str (merge request
                         {:status        status
                          :message       status-msg
                          :response-host host
                          :response-port port})
                  :key-fn (comp kebab->camel name)))

(defn- send-gridfire-response! [{:keys [response-host response-port] :as request} config status status-msg]
  (when (spec/valid? ::server-spec/gridfire-server-response-minimal request)
    (sockets/send-to-server! response-host
                             response-port
                             (build-gridfire-response request config status status-msg))))

;;=============================================================================
;; Process override-config
;;=============================================================================

(defn- add-ignition-start-timestamp [config ignition-date-time]
  (assoc config :ignition-start-timestamp ignition-date-time))

(defn- calc-weather-start-timestamp [ignition-date-time]
  (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
    (.setTime ignition-date-time)
    (.set Calendar/MINUTE 0)))

(defn- add-weather-start-timestamp [config ignition-date-time]
  (assoc config :weather-start-timestamp (calc-weather-start-timestamp ignition-date-time)))

(defn- write-config! [output-file config]
  (log-str "Writing to config file: " output-file)
  (with-open [writer (io/writer output-file)]
    (pprint config writer)))

(defn- process-override-config! [{:keys [ignition-time] :as _request} file]
  (let [formatter          (SimpleDateFormat. "yyyy-MM-dd HH:mm zzz")
        ignition-date-time (.parse formatter ignition-time)
        config             (edn/read-string (slurp file))]
    (write-config! file
                   (-> config
                       (add-ignition-start-timestamp ignition-date-time)
                       (add-weather-start-timestamp ignition-date-time)))))

;;=============================================================================
;; Process suppression-params
;;=============================================================================

(defn- add-suppression [config {:keys [suppression-dt suppression-coefficient] :as _suppression-params}]
  (assoc config :suppression {:suppression-dt          suppression-dt
                              :suppression-coefficient suppression-coefficient}))

(defn- update-suppression-params! [gridfire-edn-path {:keys [suppression] :as _request}]
  (let [config (edn/read-string (slurp gridfire-edn-path))]
    (if suppression
      (write-config! gridfire-edn-path (add-suppression config suppression))
      config)))

;;=============================================================================
;; Shell Commands
;;=============================================================================

(def path-env (System/getenv "PATH"))

(defn- sh-wrapper [dir env verbose & commands]
  (sh/with-sh-dir dir
    (sh/with-sh-env (merge {:PATH path-env} env)
      (reduce (fn [acc cmd]
                (let [{:keys [out err]} (apply sh/sh (parse-as-sh-cmd cmd))]
                  (str acc (when verbose out) err)))
              ""
              commands))))

;;=============================================================================
;; Request Processing Functions
;;=============================================================================

(defn- run-post-process-scripts! [request config output-dir]
  (log-str "Running post-process scripts.")
  (let [commands [["./elmfire_post.sh" "Running elmfire_post."]
                  ["./make_tifs.sh" "Creating GeoTIFFs."]
                  ["./build_geoserver_directory.sh"]
                  ["./upload_tarball.sh"]
                  ["./cleanup.sh"]]]
    (doseq [[cmd response-msg] commands]
      (when response-msg
        (send-gridfire-response! request config 2 response-msg))
      (-> (sh-wrapper output-dir {} true cmd)
          (log :truncate? false :newline? false)))))

(defn- copy-post-process-scripts! [from-dir to-dir]
  (log-str "Copying post-process scripts into " to-dir)
  (sh-wrapper from-dir
              {}
              false
              (str "cp resources/elmfire_post.sh " to-dir)
              (str "cp resources/make_tifs.sh " to-dir)
              (str "cp resources/build_geoserver_directory.sh " to-dir)
              (str "cp resources/upload_tarball.sh " to-dir)
              (str "cp resources/cleanup.sh " to-dir)))

(defn- build-file-name [fire-name ignition-time]
  (str/join "_" [fire-name (convert-date-string ignition-time date-from-format date-to-format) "001"]))

(defn- unzip-tar!
  "Unzips a tar file and returns the file path to the extracted folder."
  [{:keys [fire-name ignition-time type suppression] :as _request}
   {:keys [data-dir incoming-dir active-fire-dir] :as _config}]
  (let [working-dir   (if (= type :active-fire) active-fire-dir incoming-dir)
        tar-file-name (str (build-file-name fire-name ignition-time) ".tar")
        out-file-name (build-file-name (if suppression (str fire-name "-suppressed") fire-name)
                                       ignition-time)
        out-file-path (.getAbsolutePath (io/file data-dir out-file-name))]
    (if (.exists (io/file working-dir tar-file-name))
      (do
        (sh/sh "mkdir" "-p" out-file-path)
        (sh-wrapper working-dir
                    {}
                    true
                    (format "tar -xf %s -C %s --strip-components 1"
                            tar-file-name
                            out-file-path))
        (.getPath (io/file data-dir out-file-name)))
      (throw (Exception. (str "tar file does not exist: " (.getAbsolutePath (io/file working-dir tar-file-name))))))))

;;TODO Try babashka's pod protocol to see if it's faster than shelling out.
(defn- process-request!
  "Runs the requested simulation using the supplied config.

  WARNING: because each simulation requires exclusive access to various resources (e.g all the processors),
  do not make several parallel calls to this function."
  [request {:keys [software-dir override-config backup-dir] :as config}]
  (try
    (let [input-deck-path     (unzip-tar! request config)
          elmfire-data-file   (.getPath (io/file input-deck-path "elmfire.data"))
          gridfire-edn-file   (.getPath (io/file input-deck-path "gridfire.edn"))
          gridfire-output-dir (.getPath (io/file input-deck-path "outputs"))
          {:keys [err out]}   (if override-config
                                (do
                                  (process-override-config! request override-config)
                                  (sh/sh "resources/elm_to_grid.clj" "-e" elmfire-data-file "-o" override-config)
                                  (update-suppression-params! gridfire-edn-file request))
                                (sh/sh "resources/elm_to_grid.clj" "-e" elmfire-data-file))]
      (if err
        (log-str out "\n" err)
        (log-str out))
      (send-gridfire-response! request config 2 "Running simulation.")
      (if (gridfire/process-config-file! gridfire-edn-file) ; Returns true on success
        (do (copy-post-process-scripts! software-dir gridfire-output-dir)
            (run-post-process-scripts! request config gridfire-output-dir)
            (when backup-dir
              (sh/sh "tar" "-czf"
                     (str backup-dir "/" (.getName (io/file input-deck-path)) ".tar.gz")
                     (.getAbsolutePath (io/file input-deck-path))
                     (str (.getName (io/file input-deck-path)) ".tar.gz") ))
            (sh/sh "rm" "-rf" (.getAbsolutePath (io/file input-deck-path)))
            [0 "Successful run! Results uploaded to GeoServer!"])
        (throw-message "Simulation failed. No results uploaded to GeoServer.")))
    (catch Exception e
      [1 (str "Processing error: " (ex-message e))])))

;;=============================================================================
;; Job Queue Management
;;=============================================================================

(defonce *job-queue-size      (atom 0))
(defonce *stand-by-queue-size (atom 0))

(defonce =job-queue=
         (chan 10 (map (fn [x]
                         (swap! *job-queue-size inc)
                         (delay (swap! *job-queue-size dec) x)))))

(defonce =stand-by-queue=
         (chan 10 (map (fn [x]
                         (swap! *stand-by-queue-size inc)
                         (delay (swap! *stand-by-queue-size dec) x)))))

(defonce *server-running? (atom false))

(defn- process-requests-loop!
  "Starts a logical process which listens to queued requests and processes them.

  Requests are processed in order of priority, then FIFO.

  Returns a core.async channel which will close when the server is stopped."
  [config]
  (reset! *server-running? true)
  (thread
    (loop [request @(first (alts!! [=job-queue= =stand-by-queue=] :priority true))]
      (log-str "Processing request: " request)
      (let [[status status-msg] (process-request! request config)]
        (log-str "-> " status-msg)
        (if (= (:type request) :active-fire)
          (send-geosync-request! request config)
          (send-gridfire-response! request config status status-msg)))
      (when @*server-running?
        (recur @(first (alts!! [=job-queue= =stand-by-queue=] :priority true)))))))

(defn- maybe-add-to-queue! [request]
  (try
    (if (spec/valid? ::server-spec/gridfire-server-request request)
      (do (>!! =job-queue= request)
          [2 (format "Added to job queue. You are number %d in line." @*job-queue-size)])
      [1 (str "Invalid request: " (spec/explain-str ::server-spec/gridfire-server-request request))])
    (catch AssertionError _
      [1 "Job queue limit exceeded! Dropping request!"])
    (catch Exception e
      [1 (str "Validation error: " (ex-message e))])))

(defn- parse-request-msg
  "Parses the given JSON-encoded request message, returning a request map (or nil in case of invalid JSON)."
  [request-msg]
  (-> request-msg
      (json/read-str :key-fn (comp keyword camel->kebab))
      (nil-on-error)))

;; Logically speaking, this function does (process-request! (parse-request-msg request-msg) config).
;; However, in order to both limit the load and send progress-notification responses before completion,
;; the handling goes through various queues and worker threads.
(defn- schedule-handling! [config request-msg]
  (thread
    (log-str "Request: " request-msg)
    (if-let [request (parse-request-msg request-msg)]
      (let [[status status-msg] (maybe-add-to-queue! request)]
        (log-str "-> " status-msg)
        (send-gridfire-response! request config status status-msg))
      (log-str "-> Invalid JSON"))))

;;=============================================================================
;; Start/Stop Servers
;;=============================================================================

(defn start-server! [{:keys [log-dir port] :as config}]
  (when log-dir (set-log-path! log-dir))
  (log-str "Running server on port " port)
  (active-fire-watcher/start! config =stand-by-queue=)
  (sockets/start-server! port (fn [request-msg] (schedule-handling! config request-msg)))
  (process-requests-loop! config))

(defn stop-server! []
  (reset! *server-running? false)
  (sockets/stop-server!)
  (active-fire-watcher/stop!))

;; TODO write spec for server
;; Sample config
#_{:software-dir               "/home/kcheung/work/code/gridfire"
 :incoming-dir               "/home/kcheung/work/servers/chickadee/incoming"
 :active-fire-dir            "/home/kcheung/work/servers/chickadee/incoming/active_fires"
 :data-dir                   "/home/kcheung/work/servers/chickadee/data"
 :override-config            "/home/kcheung/work/servers/chickadee/gridfire-base.edn"
 :log-dir                    "/home/kcheung/work/servers/chickadee/log"
 :backup-dir                 "/home/kcheung/work/servers/chickadee/backup-to-ftp"
 :suppression-white-list     "/home/kcheung/work/servers/chickadee/suppression-white-list.edn"
   :also-simulate-suppression? true}
;; gridfire.server.pyrecast-async ends here
