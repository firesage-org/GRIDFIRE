(ns gridfire.server
  (:require [gridfire.simple-sockets :as sockets]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.core.async :refer [timeout chan >! <! go]]
            [clojure.data.json :as json]
            [clojure.string :as str]))

;;-----------------------------------------------------------------------------
;; Utils
;;-----------------------------------------------------------------------------

(defn camel->kebab
  "Converts camelString to kebab-string"
  [camel-string]
  (as-> camel-string s
    (str/split s #"(?<=[a-z])(?=[A-Z])")
    (map str/lower-case s)
    (str/join "-" s)))

(defn kebab->camel
  "Converts kebab-string to camelString."
  [kebab-string]
  (let [words (-> kebab-string
                  (str/lower-case)
                  (str/replace #"^[^a-z_$]|[^\w-]" "")
                  (str/split #"-"))]
    (->> (map str/capitalize (rest words))
         (cons (first words))
         (str/join ""))))

;;-----------------------------------------------------------------------------
;; Main
;;-----------------------------------------------------------------------------

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 31337
    :parse-fn #(if (int? %) % (Integer/parseInt %))
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-h" "--host HOST" "Host domain name"]])

(defonce job-queue (chan 10))

;; TODO This process should, after receiving response from provisioning server needs to:
;; unzip tar file in incoming and put into data folder
;; run gridfire.config/write-config to convert elmfire.data -> gridfire.edn
;; run gridfire simulation with gridfire.edn
;; run postprocess.sh to convert binary files to geotiffs and sends it to geoserver?
(defn process-requests! [{:keys [host]}]
  (go (loop [{:keys [fire-name response-host response-port] :as message} (<! job-queue)]
        (<! (timeout 500))
        (println "Message:" message)
        (sockets/send-to-server! response-host
                                 (-> response-port #(if (int? %) % (Integer/parseInt %)))
                                 (json/write-str {:fire-name     fire-name
                                                  :response-host host
                                                  :status        0}
                                                 :key-fn (comp kebab->camel name)))
        (recur (<! job-queue)))))

(defn handler [msg]
  (let [request (json/read-str msg :key-fn (comp keyword camel->kebab))]
    (go (>! job-queue request))))

(defn start-server! [& args]
  (let [{:keys [options summary errors]} (parse-opts args cli-options)]
    (if (or (seq errors) (empty? options))
      (do
        (when (seq errors)
          (run! println errors)
          (newline))
        (println (str "Usage:\n" summary)))
      (let [port (:port options)]
        (println (format "Running server on port %s" port))
        (sockets/start-server! port handler)
        (process-requests! options)))))

(def -main start-server!)