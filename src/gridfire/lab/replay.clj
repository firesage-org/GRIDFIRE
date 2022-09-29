(ns gridfire.lab.replay
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [gridfire.core]
            [gridfire.fire-spread-optimal :refer [run-fire-spread]]
            [gridfire.magellan-bridge :refer [geotiff-raster-to-tensor]]
            [gridfire.simulations :as simulations]
            [gridfire.utils.files.tar :as utar]
            [magellan.core :refer [matrix-to-raster write-raster]]
            [tech.v3.datatype :as d]
            [tech.v3.datatype.functional :as dfn])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.io File)
           (java.time ZonedDateTime)
           (java.time.format DateTimeFormatter)
           (java.util Date)))

;;  wget ftps://vwaeselynck:MyElidedPassword@ftp.sig-gis.com/kcheung/*.gz

;;sudo apt install openjdk-17-jdk
;;sudo apt install rlwrap
;;curl -O https://download.clojure.org/install/linux-install-1.11.1.1165.sh
;;chmod +x linux-install-1.11.1.1165.sh
;;sudo ./linux-install-1.11.1.1165.sh

;;vwaeselynck@iiwi:~/gridfire-scripts/lab$ nohup clj -M:nREPL -m nrepl.cmdline --port 8888 >> ../../repl-out.txt 2>&1 &

;;vwaeselynck@iiwi:~$ mkdir gridfire.git
;;vwaeselynck@iiwi:~$ cd gridfire.git/
;;vwaeselynck@iiwi:~/gridfire.git$ git init --bare
;;$ git push sigvm --all
;;vwaeselynck@iiwi:~$ git clone gridfire.git/
;;Cloning into 'gridfire'...
;;vwaeselynck@iiwi:~/gridfire$ nohup clj -M:nREPL:mydev:gridfire-my-dev -m nrepl.cmdline --port 8888 >> ../repl-out.txt 2>&1 &
;;vwaeselynck@iiwi:~$ cd gridfire
;;vwaeselynck@iiwi:~/gridfire$ git checkout vwaeselynck-330-gridfire-historical-fires

;; FIXME choose from-snapshot to-snapshot pairs from dir
;; FIXME compute difference metrics from results.
;; - Actual: burn_history < stop-inst raster
;; - Expected: active_fire_polygon_posnegbuff

;; ------------------------------------------------------------------------------
;; File and CLI utilities

;; FIXME move those to generic ns?
(defn sh-safe
  [& args]
  (let [ret (apply sh/sh args)]
    (if (not= 0 (:exit ret))
      (let [err-string (or (not-empty (:err ret)) (:out ret))]
        (throw (ex-info
                (format "Error running sh command: %s" err-string)
                (merge
                 {:sh-args (vec args)}
                 ret))))
      ret)))

(defn file-name
  [^File file]
  (.getName file))

(defn file-abs-path
  ^String [f]
  (.getCanonicalPath (io/file f)))

(defn rm-file-if-exists!
  [f]
  (let [file (io/file f)]
    (when (.exists file)
      (.delete file))))

(defn ensure-file
  [f]
  (let [ret (io/file f)]
    (when-not (.exists ret)
      (throw (ex-info (format "File does not exist: %s" (.getPath ret))
                      {:file  ret
                       :f-arg f})))
    f))

(defn archive-entries->files!
  "Saves archive entries as files in a directory, optionally returning a subset of the created files in a map.

  Given:
  - target-dir: the directory into which to write the files,
  - return-key-fn: a function,
  - archive-entries: a sequence of [entry-name entry-bytes] tuples,

  will create one file or directory per entry under target-dir,
  at relative path given by entry-name, and content entry-bytes.
  Returns a map; whenever (return-key-fn entry-name) returns a non-nil value entry-k,
  the map will contain the created java.io.File at key entry-k."
  [target-dir return-key-fn archive-entries]
  (reduce
   (fn write-entry-to-file! [returned-files [entry-name entry-bytes]]
     (let [entry-file (io/file target-dir entry-name)]
       (io/make-parents entry-file)
       (let [is-dir? (str/ends-with? entry-name "/")]
         (if is-dir?
           (.mkdir entry-file)
           (io/copy entry-bytes entry-file)))
       (merge returned-files
              (when-some [ret-k (return-key-fn entry-name)]
                {ret-k entry-file}))))
   {}
   archive-entries))

;; ------------------------------------------------------------------------------
;; Extracting PyreCast snapshots

(defn list-fire-snapshots-from-dir
  [pyrecast-snapshots-dir]
  (let [decks-dir (ensure-file pyrecast-snapshots-dir)]
    (->> (file-seq decks-dir)
         (filter #(-> (file-name %) (str/ends-with? ".tar.gz")))
         (map
          (fn [^File input-deck-targz]
            (let [file-name  (.getName input-deck-targz)
                  name-cpnts (re-matches #"(.+)_(\d{8})_(\d{6})_(\d{3})\.tar\.gz" file-name)]
              (if (nil? name-cpnts)
                (throw (ex-info (format "Failed to parse PyreCast snapshot filename: %s" (pr-str file-name))
                                {:pyrcst_snapshot_archive_name file-name}))
                (let [[_ fire-name fire-date-str fire-time-str subnum] name-cpnts]
                  {:pyrcst_snapshot_archive_name file-name
                   :pyrcst_fire_name             fire-name
                   :pyrcst_fire_subnumber        (Long/parseLong subnum 10)
                   :pyrcst_snapshot_inst         (-> (str fire-date-str " " fire-time-str " Z")
                                                     (ZonedDateTime/parse (DateTimeFormatter/ofPattern "yyyyMMdd HHmmss z"))
                                                     (.toInstant)
                                                     (Date/from))
                   :pyrcst_snapshot_archive      input-deck-targz})))))
         (sort-by (juxt :pyrcst_fire_name :pyrcst_fire_subnumber :pyrcst_snapshot_inst))
         (vec))))


(defn sanitize-pyrecast-snapshot-entries
  "Filters, renames and rewrites the TAR entries for a PyreCast snapshot
  into a locally-runnable input deck.

  Accepts and returns a sequence of [entry-name entry-bytes] pairs."
  [snap-entries]
  (let [tar-entry-prefix   "home/gridfire_data/"
        config-path-prefix "/home/gridfire/data/"]
    (->> snap-entries
         (map
          (fn drop-prefix-path [[entry-name entry-bytes]]
            [(subs entry-name (count tar-entry-prefix))
             entry-bytes]))
         (remove
          (fn is-unneeded-file? [[entry-name _entry-bytes]]
            (let [[_ _snapshot-name rel-path] (re-matches #"([^\/]+)\/(.*)" entry-name)]
              (re-matches #"outputs/.+" rel-path))))
         (map
          (fn repath-gridfire-edn [entry-pair]
            (let [[entry-name entry-bytes] entry-pair
                  [_ snapshot-name _rel-path] (re-matches #"([^\/]+)\/(.*)" entry-name)]
              (if (str/ends-with? entry-name "/gridfire.edn")
                [entry-name
                 (-> (String. (bytes entry-bytes) "UTF-8")
                     (str/replace (str "\"" config-path-prefix snapshot-name "/") "#gridfire.utils.files/from-this-file \"")
                     (.getBytes "UTF-8"))]
                entry-pair)))))))

(defn interesting-file-key
  [entry-name]
  (cond
    (str/ends-with? entry-name "/gridfire.edn")
    ::gridfire-config-file

    (str/ends-with? entry-name "/active_fire_polygon_utm_posnegbuff.shp")
    ::active_fire_polygon_utm_posnegbuff-shp

    (str/ends-with? entry-name "/fuels_and_topography/already_burned.tif")
    ::already_burned-tif))


(defn extract-pyrecast-snapshots!
  [pyrecast-snapshots-dir]
  (let [tmp-dir (.toFile (Files/createTempDirectory (-> (io/file "../tmp-gridfire-replay") (doto (.mkdirs)) (.toPath) (.toAbsolutePath))
                                                    "extracted-snapshots"
                                                    (into-array FileAttribute [])))]
    (->> (list-fire-snapshots-from-dir pyrecast-snapshots-dir)
         (pmap
          (fn extract-snapshot! [snap]
            (merge
             snap
             {::useful-files
              (with-open [tais (utar/targz-input-stream (:pyrcst_snapshot_archive snap))]
                (->> (utar/tar-archive-entries tais)
                     (sanitize-pyrecast-snapshot-entries)
                     (archive-entries->files! tmp-dir interesting-file-key)))})))
         (vec))))


;; ------------------------------------------------------------------------------
;; Comparing observed and simulated burn areas

(defn config-max-runtime-until-stop-inst
  [inputs stop-inst]
  (assoc inputs :max-runtime (/
                              (-
                               (inst-ms stop-inst)
                               (->> [:ignition-start-timestamp
                                     :weather-start-timestamp]
                                    (keep #(get inputs %))
                                    (map inst-ms)
                                    (apply min)))
                              (* 1e3 60))))

(defn run-fire-spread-until
  [config stop-inst]
  (some-> config
          (config-max-runtime-until-stop-inst stop-inst)
          (gridfire.core/load-inputs!)
          (as-> inputs
                {::simulations/inputs inputs
                 ::simulations/result
                 (-> (simulations/prepare-simulation-inputs 0 inputs)
                     (run-fire-spread))})))



(defn observed-burned-area-matrix
  "Computes the observed burned area between 2 PyreCast snapshots (from t0 to t1),
  as the set difference between active-fire-at-t1 and already-burned-at-t0.

  Accepts files and returns a GIS-enveloped matrix."
  [tmp-dir t0-already_burned-tif t1-active_fire_polygon_utm_posnegbuff-shp]
  (let [t0-already_burned-tif                     (ensure-file t0-already_burned-tif)
        ;; NOTE 'posnegbuff' hints at the algorithm used for approximating the perimeter from the original hot-spots polygons,
        ;; which tend to paint a dotted landscape; a succession of buffering and negative buffering is used to approximate
        ;; the perimeter from these polygons. See script 01-setup_run.sh in Elmfire input decks. (Val, 29 Sep 2022)
        t1-active_fire_polygon_utm_posnegbuff-shp (ensure-file t1-active_fire_polygon_utm_posnegbuff-shp)
        t1-active_fire_posnegbuff-tif             (io/file tmp-dir "t1-active_fire_polygon_utm_posnegbuff.tif")
        expected-burned-area-tif                  (io/file tmp-dir "expected-burned-area.tif")]
    (try
      ;; NOTE I chose GDAL over PostGis for these computations, (Val, 29 Sep 2022)
      ;; because I judged that the GDAL executables are easier to take for granted
      ;; than a running Postgres server.
      ;; Writing a GeoTiff with zeroes
      (sh-safe "gdal_calc.py" "-A" (file-abs-path t0-already_burned-tif)
               "--NoDataValue=255"
               "--type=Byte"
               "--calc=A*0"
               "--overwrite"
               (str "--outfile=" (file-abs-path t1-active_fire_posnegbuff-tif)))
      ;; Burning the polygon into it
      (sh-safe "gdal_rasterize" "-burn" "1" "-at"
               (file-abs-path t1-active_fire_polygon_utm_posnegbuff-shp)
               (file-abs-path t1-active_fire_posnegbuff-tif))
      ;; Computing the expected burned area
      (sh-safe "gdal_calc.py"
               "-A" (file-abs-path t0-already_burned-tif)
               "-B" (file-abs-path t1-active_fire_posnegbuff-tif)
               "--NoDataValue=255"
               "--type=Byte"
               "--calc=(A==0)*(B==1)"                       ; set difference
               (str "--outfile=" (file-abs-path expected-burned-area-tif)))
      (geotiff-raster-to-tensor (file-abs-path expected-burned-area-tif) :float32 identity)
      (finally
        (run! rm-file-if-exists!
              [t1-active_fire_posnegbuff-tif
               expected-burned-area-tif])))))

(defn simulated-burned-area-matrix
  [stop-inst sim-inputs sim-result]
  (let [minutes-until-stop-inst (/ (-
                                    (inst-ms stop-inst)
                                    (inst-ms (first (:ignition-start-timestamps sim-inputs))))
                                   (* 1e3 60))]
    (dfn/max ; set union
     (:ignition-matrix sim-inputs)
     (d/elemwise-cast
      (dfn/<=
       0.
       (:burn-time-matrix sim-result)
       minutes-until-stop-inst)
      :double))))


(defn replay-snapshot
  [t0-snap t1-snap]
  (let [stop-inst            (:pyrcst_snapshot_inst t1-snap)
        observed-burn-area   (observed-burned-area-matrix
                              ;; FIXME cleanup
                              (.toFile (Files/createTempDirectory (-> (io/file "../tmp-gridfire-replay") (doto (.mkdirs)) (.toPath) (.toAbsolutePath))
                                                                  "scratch_observed-burned-area-matrix"
                                                                  (into-array FileAttribute [])))
                              (-> t0-snap ::useful-files ::already_burned-tif)
                              (-> t1-snap ::useful-files ::active_fire_polygon_utm_posnegbuff-shp))
        observed-burn-matrix (:matrix observed-burn-area)
        config               (-> (gridfire.core/load-config! (-> t0-snap
                                                                 ::useful-files
                                                                 ::gridfire-config-file
                                                                 (ensure-file)
                                                                 (.getCanonicalPath)))
                                 (into (sorted-map))
                                 (dissoc :perturbations)
                                 ; FIXME shall we run them all? Probably want more control over that.
                                 (merge {:simulations 1}))
        sim-output           (run-fire-spread-until config stop-inst)
        sim-result  (::simulations/result sim-output)]
    (if (nil? sim-result)
      {:observed-burn-n-cells (dfn/sum observed-burn-matrix)
       :sim-inter-obs-n-cells 0
       :sim-minus-obs-n-cells 0
       :obs-minus-sim-n-cells (dfn/sum observed-burn-matrix)
       ::comments             ["run-fire-spread returned nil, which happens when it fails to get perimeter cells with burnable neighbours."]}
      (let [sim-burn-matrix (simulated-burned-area-matrix
                             stop-inst
                             (::simulations/inputs sim-output)
                             sim-result)]
        {:observed-burn-n-cells (dfn/sum observed-burn-matrix)
         :sim-inter-obs-n-cells (dfn/sum (dfn/* sim-burn-matrix observed-burn-matrix))
         :sim-minus-obs-n-cells (dfn/sum (dfn/* sim-burn-matrix (dfn/- 1.0 observed-burn-matrix)))
         :obs-minus-sim-n-cells (dfn/sum (dfn/* observed-burn-matrix (dfn/- 1.0 sim-burn-matrix)))}))))

(comment

  (def snaps (extract-pyrecast-snapshots! "../pyrecast-snapshots"))

  (->> snaps (map ::useful-files) (mapcat keys) frequencies)
  => #:gridfire.lab.replay{:already_burned-tif 170,         ;; FIXME darn, sometimes it's missing
                           :active_fire_polygon_utm_posnegbuff-shp 172,
                           :gridfire-config-file 172}

  (def replay-results
    (-> snaps
        (->>
          (sort-by (juxt :pyrcst_fire_name :pyrcst_fire_subnumber :pyrcst_snapshot_inst))
          (partition-all 2 1)
          (filter
           (fn [[t0-snap t1-snap]]
             (and
              (= (:pyrcst_fire_name t0-snap) (:pyrcst_fire_name t1-snap))
              (= (:pyrcst_fire_subnumber t0-snap) (:pyrcst_fire_subnumber t1-snap))
              (-> t0-snap ::useful-files ::already_burned-tif (some?))
              (-> t0-snap ::useful-files ::gridfire-config-file (some?))
              (-> t1-snap ::useful-files ::active_fire_polygon_utm_posnegbuff-shp (some?))
              (let [time-lapse-ms (- (-> t1-snap :pyrcst_snapshot_inst (inst-ms))
                                     (-> t0-snap :pyrcst_snapshot_inst (inst-ms)))
                    ms-3hr        (* 1000 60 60)
                    ms-3days      (* 1000 60 60 24 3)]
                (<= ms-3hr time-lapse-ms ms-3days))))))
        (doto (-> (count) (println "comparable t0->t1 pairs")))
        (->>
          ;(sort-by hash) (take 3)
          (pmap
           (fn compare-snapshots [[t0-snap t1-snap]]
             (println "Replaying" (:pyrcst_fire_name t0-snap) "from" (-> t0-snap :pyrcst_snapshot_inst) "to" (-> t1-snap :pyrcst_snapshot_inst))
             (merge
              (select-keys t0-snap [:pyrcst_fire_name :pyrcst_fire_subnumber])
              {::t0-fire (select-keys t0-snap [:pyrcst_snapshot_inst])
               ::t1-fire (select-keys t1-snap [:pyrcst_snapshot_inst])}
              (replay-snapshot t0-snap t1-snap))))
          (vec))))

  (require 'clojure.pprint)
  (clojure.pprint/print-table
   (->> replay-results
        (mapv (fn [res]
                {"Fire name"             (:pyrcst_fire_name res)
                 "t0"                    (-> res ::t0-fire :pyrcst_snapshot_inst)
                 "t1"                    (-> res ::t1-fire :pyrcst_snapshot_inst)
                 "n cells really burned" (:observed-burn-n-cells res)
                 "sim ∩ real"            (:sim-inter-obs-n-cells res)
                 "sim - real"            (:sim-minus-obs-n-cells res)
                 "real - sim"            (:obs-minus-sim-n-cells res)}))))

  *e)


(comment

  (def config-file
    (let [tmp-dir (.toFile (Files/createTempDirectory "gridfire-runs-tmp" (into-array FileAttribute [])))]
      (with-open [tais (utar/targz-input-stream "../wa-minnow-ridge_20220914_212100_001.tar.gz")]
        (->> (utar/tar-archive-entries tais)
             (sanitize-pyrecast-snapshot-entries)
             (archive-entries->files!
              tmp-dir
              (fn [entry-name]
                (when (str/ends-with? entry-name "/gridfire.edn")
                  ::gridfire-config-file)))
             ::gridfire-config-file))))

  (gridfire.core/load-config! (.getCanonicalPath config-file))

  (def stop-inst #inst"2022-09-16T10:06:00.000-00:00")

  (def sim-output
    (let [config (-> (gridfire.core/load-config! (.getCanonicalPath config-file))
                     (into (sorted-map))
                     (merge {:simulations      1
                             :output-layers    {:burn-history :final}
                             :output-geotiffs? true
                             :output-binary?   false}))]
      (run-fire-spread-until config stop-inst)))

  (tech.v3.datatype.functional/descriptive-statistics (:burn-time-matrix (::simulations/result sim-output)))
  =>
  {:min -1.0,
   :mean 0.40083510717508014,
   :standard-deviation 47.38723989094369,
   :max 2099.9979687877767,
   :n-values 5817744}

  (into (sorted-map)
        (map (fn [[k v]]
               [k (type v)]))
        (::simulations/result sim-output))
  =>
  {:burn-time-matrix                tech.v3.tensor_api.DirectTensor,
   :crown-fire-count                java.lang.Double,
   :directional-flame-length-matrix nil,
   :exit-condition                  clojure.lang.Keyword,
   :fire-line-intensity-matrix      tech.v3.tensor_api.DirectTensor,
   :fire-spread-matrix              tech.v3.tensor_api.DirectTensor,
   :fire-type-matrix                tech.v3.tensor_api.DirectTensor,
   :flame-length-matrix             tech.v3.tensor_api.DirectTensor,
   :global-clock                    java.lang.Double,
   :spot-count                      java.lang.Long,
   :spot-matrix                     tech.v3.tensor_api.DirectTensor,
   :spread-rate-matrix              tech.v3.tensor_api.DirectTensor,
   :surface-fire-count              java.lang.Double}

  (tech.v3.tensor/mget (:wind-speed-20ft-matrix (::simulations/inputs sim-output)) 103)

  (tech.v3.datatype.functional/descriptive-statistics
   (->> (::simulations/inputs sim-output) :ignition-matrix))

  (tech.v3.datatype.functional/descriptive-statistics *1)

  ;; actual burn matrix
  (-> (matrix-to-raster "actual-burn-trace-boolean"
                        (simulated-burned-area-matrix
                         stop-inst
                         (::simulations/inputs sim-output)
                         (::simulations/result sim-output))
                        (:envelope (::simulations/inputs sim-output)))
      (write-raster "../actual-burn-trace-boolean2.tif"))

  (def target-file "../FIXME_active_fire.tif")
  (def observed-burn-matrix
    (observed-burned-area-matrix
     ".."
     (-> (io/file config-file) (.getParentFile) (io/file "fuels_and_topography/already_burned.tif"))
     (str "/Users/val/projects/SIG/historical-fire/fire-perimeters-lightweight/fire-perimeters-lightweight/wa-minnow-ridge/20220916_100600_001/fuels_and_topography/"
          "active_fire_polygon_utm_posnegbuff.shp")))

  (dfn/sum (:matrix observed-burn-matrix))
  => 652.0

  (def sim-burn-matrix
    (simulated-burned-area-matrix
     stop-inst
     (::simulations/inputs sim-output)
     (::simulations/result sim-output)))


  (.exists (io/file config-file "../fuels_and_topography/already_burned.tif"))
  ;;=> false
  (.exists (-> (io/file config-file) (str) (str "/../fuels_and_topography/already_burned.tif") (io/file)))
  ;;=> false

  *e)
