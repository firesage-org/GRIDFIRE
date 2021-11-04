(ns gridfire.outputs
  (:require [clojure.core.matrix :as m]
            [clojure.data.csv    :as csv]
            [clojure.java.io     :as io]
            [clojure.string      :as str]
            [magellan.core       :refer [matrix-to-raster write-raster]]
            [matrix-viz.core     :refer [save-matrix-as-png]]))

(m/set-current-implementation :vectorz)

#_(set! *unchecked-math* :warn-on-boxed)

(defn output-filename [name outfile-suffix simulation-id output-time ext]
  (as-> [name outfile-suffix simulation-id (when output-time (str "t" output-time))] $
    (remove str/blank? $)
    (str/join "_" $)
    (str $ ext)))

(defn output-geotiff
  ([config matrix name envelope]
   (output-geotiff config matrix name envelope nil nil))

  ([config matrix name envelope simulation-id]
   (output-geotiff config matrix name envelope simulation-id nil))

  ([{:keys [output-directory outfile-suffix] :as config}
    matrix name envelope simulation-id output-time]
   (let [file-name (output-filename name
                                    outfile-suffix
                                    (str simulation-id)
                                    output-time
                                    ".tif")]
     (-> (matrix-to-raster name matrix envelope)
         (write-raster (if output-directory
                         (str/join "/" [output-directory file-name])
                         file-name))))))

(defn output-png
  ([config matrix name envelope]
   (output-png config matrix name envelope nil nil))

  ([config matrix name envelope simulation-id]
   (output-png config matrix name envelope simulation-id nil))

  ([{:keys [output-directory outfile-suffix]}
    matrix name envelope simulation-id output-time]
   (let [file-name (output-filename name
                                    outfile-suffix
                                    (str simulation-id)
                                    output-time
                                    ".png")]
     (save-matrix-as-png :color 4 -1.0
                         matrix
                         (if output-directory
                           (str/join "/" [output-directory file-name])
                           (file-name))))))

(defn write-landfire-layers!
  [{:keys [output-landfire-inputs? outfile-suffix landfire-rasters envelope]}]
  (when output-landfire-inputs?
    (doseq [[layer matrix] landfire-rasters]
      (-> (matrix-to-raster (name layer) matrix envelope)
          (write-raster (str (name layer) outfile-suffix ".tif"))))))

(defn write-burn-probability-layer!
  [{:keys [output-burn-probability simulations envelope output-pngs?] :as inputs} {:keys [burn-count-matrix]}]
  (when-let [timestep output-burn-probability]
    (let [output-name "burn_probability"]
      (if (int? timestep)
        (doseq [[band matrix] (map-indexed vector burn-count-matrix)]
          (let [output-time        (* band timestep)
                probability-matrix (m/emap #(/ % simulations) matrix)]
            (output-geotiff inputs probability-matrix output-name envelope nil output-time)
            (output-png inputs probability-matrix output-name envelope nil output-time)))
        (let [probability-matrix (m/emap #(/ % simulations) burn-count-matrix)]
          (output-geotiff inputs probability-matrix output-name envelope)
          (when output-pngs?
            (output-png inputs probability-matrix output-name envelope)))))))

(defn write-flame-length-sum-layer!
  [{:keys [envelope output-flame-length-sum?] :as inputs}
   {:keys [flame-length-sum-matrix]}]
  (when output-flame-length-sum?
    (output-geotiff inputs flame-length-sum-matrix "flame_length_sum" envelope)))

(defn write-flame-length-max-layer!
  [{:keys [envelope output-flame-length-max?] :as inputs}
   {:keys [flame-length-max-matrix]}]
  (when output-flame-length-max?
    (output-geotiff inputs flame-length-max-matrix "flame_length_max" envelope)))

(defn write-burn-count-layer!
  [{:keys [envelope output-burn-count?] :as inputs}
   {:keys [burn-count-matrix]}]
  (when output-burn-count?
    (output-geotiff inputs burn-count-matrix "burn_count" envelope)))

(defn write-spot-count-layer!
  [{:keys [envelope output-spot-count?] :as inputs}
   {:keys [spot-count-matrix]}]
  (when output-spot-count?
    (output-geotiff inputs spot-count-matrix "spot_count" envelope)))

(defn write-aggregate-layers!
  [inputs outputs]
  (write-burn-probability-layer! inputs outputs)
  (write-flame-length-sum-layer! inputs outputs)
  (write-flame-length-max-layer! inputs outputs)
  (write-burn-count-layer! inputs outputs)
  (write-spot-count-layer! inputs outputs))

(defn write-csv-outputs!
  [{:keys [output-csvs? output-directory outfile-suffix]} {:keys [summary-stats]}]
  (when output-csvs?
    (let [output-filename (str "summary_stats" outfile-suffix ".csv")]
      (with-open [out-file (io/writer (if output-directory
                                        (str/join "/" [output-directory output-filename])
                                        output-filename))]
        (->> summary-stats
             (sort-by #(vector (:ignition-row %) (:ignition-col %)))
             (mapv (fn [{:keys [ignition-row ignition-col max-runtime temperature relative-humidity
                                wind-speed-20ft wind-from-direction foliar-moisture ellipse-adjustment-factor
                                fire-size flame-length-mean flame-length-stddev fire-line-intensity-mean
                                fire-line-intensity-stddev simulation crown-fire-size spot-count]}]
                     [simulation
                      ignition-row
                      ignition-col
                      max-runtime
                      temperature
                      relative-humidity
                      wind-speed-20ft
                      wind-from-direction
                      foliar-moisture
                      ellipse-adjustment-factor
                      fire-size
                      flame-length-mean
                      flame-length-stddev
                      fire-line-intensity-mean
                      fire-line-intensity-stddev
                      crown-fire-size
                      spot-count]))
             (cons ["simulation" "ignition-row" "ignition-col" "max-runtime" "temperature" "relative-humidity"
                    "wind-speed-20ft" "wind-from-direction" "foliar-moisture" "ellipse-adjustment-factor"
                    "fire-size" "flame-length-mean" "flame-length-stddev" "fire-line-intensity-mean"
                    "fire-line-intensity-stddev" "crown-fire-size" "spot-count"])
             (csv/write-csv out-file))))))
