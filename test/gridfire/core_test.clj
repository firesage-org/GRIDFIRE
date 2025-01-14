;; [[file:../../org/GridFire.org::gridfire.core-test][gridfire.core-test]]
(ns gridfire.core-test
  (:require [clojure.string              :as str]
            [clojure.test                :refer [deftest is testing use-fixtures are compose-fixtures]]
            [gridfire.binary-output      :as binary]
            [gridfire.conversion         :refer [m->ft]]
            [gridfire.core               :as core]
            [gridfire.fetch              :as fetch]
            [gridfire.utils.test         :as utils]
            [tech.v3.datatype.functional :as dfn]))

;;-----------------------------------------------------------------------------
;; Config
;;-----------------------------------------------------------------------------

(def resources-path "test/gridfire/resources")

(def db-spec {:classname   "org.postgresql.Driver"
              :subprotocol "postgresql"
              :subname     "//localhost:5432/gridfire_test"
              :user        "gridfire_test"
              :password    "gridfire_test"})

(def test-config-base
  {:landfire-layers           {:aspect             {:type   :geotiff
                                                    :source "test/gridfire/resources/asp.tif"}
                               :canopy-base-height {:type   :geotiff
                                                    :source "test/gridfire/resources/cbh.tif"}
                               :canopy-cover       {:type   :geotiff
                                                    :source "test/gridfire/resources/cc.tif"}
                               :canopy-height      {:type   :geotiff
                                                    :source "test/gridfire/resources/ch.tif"}
                               :crown-bulk-density {:type   :geotiff
                                                    :source "test/gridfire/resources/cbd.tif"}
                               :elevation          {:type   :geotiff
                                                    :source "test/gridfire/resources/dem.tif"}
                               :fuel-model         {:type   :geotiff
                                                    :source "test/gridfire/resources/fbfm40.tif"}
                               :slope              {:type   :geotiff
                                                    :source "test/gridfire/resources/slp.tif"}}
   :srid                      "CUSTOM:900914"
   :cell-size                 98.425     ;; (feet)
   :ignition-row              [10 10]
   :ignition-col              [20 20]
   :max-runtime               60         ;; (minutes)
   :temperature               '(50)      ;; (degrees Fahrenheit)
   :relative-humidity         '(1)       ;; (%)
   :wind-speed-20ft           '(10)      ;; (miles/hour)
   :wind-from-direction       '(0)       ;; (degrees clockwise from north)
   :foliar-moisture           90         ;; (%)
   :crowning-disabled?        false
   :ellipse-adjustment-factor 1.0        ;; (< 1.0 = more circular, > 1.0 = more elliptical)
   :simulations               1
   :random-seed               1234567890 ;; long value (optional)
   :output-csvs?              true})

;;-----------------------------------------------------------------------------
;; Utils
;;-----------------------------------------------------------------------------

(defn in-file-path [filename]
  (str/join "/" [resources-path filename]))

(defn run-test-simulation! [config]
  (let [inputs (core/load-inputs! config)]
    (map #(dissoc % :rand-gen)
         (:summary-stats (core/run-simulations! inputs)))))

(defn valid-exits? [results]
  (when (seq results)
    (every? #(#{:max-runtime-reached :no-burnable-fuels} (:exit-condition %)) results)))

(defn results-signature
  "Computes a value representing the logical behavior of the simulation."
  [results]
  (mapv (fn [r]
          (select-keys r [:crown-fire-count
                          :crown-fire-size
                          :exit-condition
                          :fire-line-intensity-mean
                          :fire-line-intensity-stddev
                          :fire-size
                          :flame-length-mean
                          :flame-length-stddev
                          :global-clock
                          :spot-count
                          :surface-fire-count
                          :surface-fire-size]))
        results))

;;-----------------------------------------------------------------------------
;; Fixtures
;;-----------------------------------------------------------------------------

(use-fixtures :once (-> utils/with-temp-output-dir
                        (compose-fixtures utils/with-reset-db-pool)
                        (compose-fixtures utils/with-register-custom-projections)))

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------

(deftest ^{:database true :simulation true} fetch-landfire-layers-test
  (testing "Fetching layers from postgis and geotiff files"
    (let [postgis-config {:db-spec         db-spec
                          :srid            "CUSTOM:900914"
                          :landfire-layers {:aspect             {:type   :postgis
                                                                 :source "landfire.asp WHERE rid=1"}
                                            :canopy-base-height {:type   :postgis
                                                                 :source "landfire.cbh WHERE rid=1"}
                                            :canopy-cover       {:type   :postgis
                                                                 :source "landfire.cc WHERE rid=1"}
                                            :canopy-height      {:type   :postgis
                                                                 :source "landfire.ch WHERE rid=1"}
                                            :crown-bulk-density {:type   :postgis
                                                                 :source "landfire.cbd WHERE rid=1"}
                                            :elevation          {:type   :postgis
                                                                 :source "landfire.dem WHERE rid=1"}
                                            :fuel-model         {:type   :postgis
                                                                 :source "landfire.fbfm40 WHERE rid=1"}
                                            :slope              {:type   :postgis
                                                                 :source "landfire.slp WHERE rid=1"}}}
          geotiff-config {:landfire-layers {:aspect             {:type   :geotiff
                                                                 :source (in-file-path "asp.tif")}
                                            :canopy-base-height {:type   :geotiff
                                                                 :source (in-file-path "cbh.tif")}
                                            :canopy-cover       {:type   :geotiff
                                                                 :source (in-file-path "cc.tif")}
                                            :canopy-height      {:type   :geotiff
                                                                 :source (in-file-path "ch.tif")}
                                            :crown-bulk-density {:type   :geotiff
                                                                 :source (in-file-path "cbd.tif")}
                                            :elevation          {:type   :geotiff
                                                                 :source (in-file-path "dem.tif")}
                                            :fuel-model         {:type   :geotiff
                                                                 :source (in-file-path "fbfm40.tif")}
                                            :slope              {:type   :geotiff
                                                                 :source (in-file-path "slp.tif")}}}]

      (is (dfn/equals (:matrix (fetch/landfire-layer postgis-config :aspect))
                      (:matrix (fetch/landfire-layer geotiff-config :aspect))))

      (is (dfn/equals (:matrix (fetch/landfire-layer postgis-config :canopy-cover))
                      (:matrix (fetch/landfire-layer geotiff-config :canopy-cover))))

      (is (dfn/equals (:matrix (fetch/landfire-layer postgis-config :canopy-height))
                      (:matrix (fetch/landfire-layer geotiff-config :canopy-height))))

      (is (dfn/equals (:matrix (fetch/landfire-layer postgis-config :crown-bulk-density))
                      (:matrix (fetch/landfire-layer geotiff-config :crown-bulk-density))))

      (is (dfn/equals (:matrix (fetch/landfire-layer postgis-config :elevation))
                      (:matrix (fetch/landfire-layer geotiff-config :elevation))))

      (is (dfn/equals (:matrix (fetch/landfire-layer postgis-config :fuel-model))
                      (:matrix (fetch/landfire-layer geotiff-config :fuel-model))))

      (is (dfn/equals (:matrix (fetch/landfire-layer postgis-config :slope))
                      (:matrix (fetch/landfire-layer geotiff-config :slope)))))))
;; TODO Add test for envelope

;;-----------------------------------------------------------------------------
;; Landfire Layer Tests
;;-----------------------------------------------------------------------------

(deftest ^{:database true :simulation true} run-test-simulation!-test
  (testing "Running simulation with different ways to fetch LANDFIRE layers"
    (let [postgis-config  (merge test-config-base
                                 {:db-spec         db-spec
                                  :landfire-layers {:aspect             {:type   :postgis
                                                                         :source "landfire.asp WHERE rid=1"}
                                                    :canopy-base-height {:type   :postgis
                                                                         :source "landfire.cbh WHERE rid=1"}
                                                    :canopy-cover       {:type   :postgis
                                                                         :source "landfire.cc WHERE rid=1"}
                                                    :canopy-height      {:type   :postgis
                                                                         :source "landfire.ch WHERE rid=1"}
                                                    :crown-bulk-density {:type   :postgis
                                                                         :source "landfire.cbd WHERE rid=1"}
                                                    :elevation          {:type   :postgis
                                                                         :source "landfire.dem WHERE rid=1"}
                                                    :fuel-model         {:type   :postgis
                                                                         :source "landfire.fbfm40 WHERE rid=1"}
                                                    :slope              {:type   :postgis
                                                                         :source "landfire.slp WHERE rid=1"}}})
          geotiff-config  (merge test-config-base
                                 {:landfire-layers {:aspect             {:type   :geotiff
                                                                         :source (in-file-path "asp.tif")}
                                                    :canopy-base-height {:type   :geotiff
                                                                         :source (in-file-path "cbh.tif")}
                                                    :canopy-cover       {:type   :geotiff
                                                                         :source (in-file-path "cc.tif")}
                                                    :canopy-height      {:type   :geotiff
                                                                         :source (in-file-path "ch.tif")}
                                                    :crown-bulk-density {:type   :geotiff
                                                                         :source (in-file-path "cbd.tif")}
                                                    :elevation          {:type   :geotiff
                                                                         :source (in-file-path "dem.tif")}
                                                    :fuel-model         {:type   :geotiff
                                                                         :source (in-file-path "fbfm40.tif")}
                                                    :slope              {:type   :geotiff
                                                                         :source (in-file-path "slp.tif")}}})
          postgis-results (run-test-simulation! postgis-config)

          geotiff-results (run-test-simulation! geotiff-config)]

      (is (valid-exits? postgis-results))

      (is (valid-exits? geotiff-results))

      (is (= (mapv :fire-size postgis-results) (mapv :fire-size geotiff-results))))))
;;-----------------------------------------------------------------------------
;; Ignition Layer Tests
;;-----------------------------------------------------------------------------

(deftest ^:simulation geotiff-ignition-test
  (testing "Running simulation with ignition layers read from geotiff files"
    (let [config (merge test-config-base
                        {:ignition-layer           {:type   :geotiff
                                                    :source (in-file-path "ign.tif")}
                         ;; While we're here, using this test case for testing :max-parallel-simulations without wasting the time of a simulation. (Val, 13 Jan 2023)
                         :max-parallel-simulations 2})]

      (is (valid-exits? (run-test-simulation! config))))))

(deftest ^{:database true :simulation true} postgis-ignition-test
  (testing "Running simulation with ignition layers read from Postgres database"
    (let [config (merge test-config-base
                        {:db-spec         db-spec
                         :ignition-layer {:type   :postgis
                                          :source "ignition.ign WHERE rid=1"}})]
      (is (valid-exits? (run-test-simulation! config))))))

(deftest ^:simulation burn-value-test
  (testing "Running simulation with burned and unburned values different from Gridfire's definition"
    (let [config (merge test-config-base
                        {:ignition-layer {:type        :geotiff
                                          :source      (in-file-path "ign-inverted.tif")
                                          :burn-values {:burned   -1.0
                                                        :unburned 1.0}}})]
      (is (valid-exits? (run-test-simulation! config))))))

;;-----------------------------------------------------------------------------
;; Weather Layer Tests
;;-----------------------------------------------------------------------------

(def landfire-layers-weather-test
  {:aspect             {:type   :geotiff
                        :source (in-file-path "weather-test/asp.tif")}
   :canopy-base-height {:type       :geotiff
                        :source     (in-file-path "weather-test/cbh.tif")
                        :unit       :metric
                        :multiplier 0.1}
   :canopy-cover       {:type   :geotiff
                        :source (in-file-path "weather-test/cc.tif")}
   :canopy-height      {:type       :geotiff
                        :source     (in-file-path "weather-test/ch.tif")
                        :unit       :metric
                        :multiplier 0.1}
   :crown-bulk-density {:type       :geotiff
                        :source     (in-file-path "weather-test/cbd.tif")
                        :unit       :metric
                        :multiplier 0.01}
   :elevation          {:type   :geotiff
                        :source (in-file-path "weather-test/dem.tif")}
   :fuel-model         {:type   :geotiff
                        :source (in-file-path "weather-test/fbfm40.tif")}
   :slope              {:type   :geotiff
                        :source (in-file-path "weather-test/slp.tif")}})

(def weather-layers
  {:temperature         {:type   :geotiff
                         :source (in-file-path "weather-test/tmpf_to_sample.tif")}
   :relative-humidity   {:type   :geotiff
                         :source (in-file-path "weather-test/rh_to_sample.tif")}
   :wind-speed-20ft     {:type   :geotiff
                         :source (in-file-path "weather-test/ws_to_sample.tif")}
   :wind-from-direction {:type   :geotiff
                         :source (in-file-path "weather-test/wd_to_sample.tif")}})

(deftest ^:simulation run-test-simulation!-weather-test
  (doseq [weather weather-layers]
    (let [config (merge test-config-base
                        weather
                        {:landfire-layers landfire-layers-weather-test
                         :max-runtime     120})]

      (is (valid-exits? (run-test-simulation! config))))))


(deftest ^:simulation geotiff-landfire-weather-ignition
  (testing "Running simulation using landfire, weather, and ignition data from geotiff files"
    (let [config (merge test-config-base
                        weather-layers
                        {:landfire-layers landfire-layers-weather-test
                         :ignition-layer  {:type   :geotiff
                                           :source (in-file-path "weather-test/phi.tif")}
                         :max-runtime     120})]

      (is (valid-exits? (run-test-simulation! config))))))

(deftest ^:simulation run-test-simulation!-using-lower-resolution-weather-test
  (testing "Running simulation using temperature data from geotiff file"
    (let [config (merge test-config-base
                        {:cell-size       (m->ft 30)
                         :landfire-layers landfire-layers-weather-test
                         :temperature     {:type   :geotiff
                                           :source (in-file-path "weather-test/tmpf_to_sample_lower_res.tif")}})]

      (is (valid-exits? (run-test-simulation! config))))))

;;-----------------------------------------------------------------------------
;; Perturbation Tests
;;-----------------------------------------------------------------------------

(deftest ^:simulation run-test-simulation!-with-landfire-perturbations
  (testing "with global perturbation value"
    (let [config (merge test-config-base
                        {:perturbations {:canopy-height {:spatial-type :global
                                                         :range        [-1.0 1.0]}}})]
      (is (valid-exits? (run-test-simulation! config)))))

  (testing "with pixel by pixel perturbation"
    (let [config (merge test-config-base
                        {:perturbations {:canopy-height {:spatial-type :pixel
                                                         :range        [-1.0 1.0]}}})]
      (is (valid-exits? (run-test-simulation! config))))))

(deftest ^:simulation run-test-simulation!-with-weather-perturbations
  (testing "temperature"
    (are [config] (valid-exits? (run-test-simulation! config))
      (merge test-config-base
             {:perturbations {:temperature {:spatial-type :global
                                            :range        [-1.0 1.0]}}})
      (merge test-config-base
             {:perturbations {:temperature {:spatial-type :smoothed-supergrid
                                            :gridfire.perturbation.smoothed-supergrid/supergrid-size [3 2 2]
                                            :range        [-1.0 1.0]}}})
      (merge test-config-base
             {:perturbations {:temperature {:spatial-type :pixel
                                            :range        [-1.0 1.0]}}})
      (merge test-config-base
             {:perturbations   {:temperature {:spatial-type :global
                                              :range        [-1.0 1.0]}}
              :landfire-layers landfire-layers-weather-test
              :temperature     (:temperature weather-layers)})
      (merge test-config-base
             {:perturbations {:temperature {:spatial-type :smoothed-supergrid
                                            :gridfire.perturbation.smoothed-supergrid/supergrid-size [1 1 1]
                                            :range        [-1.0 1.0]}}})
      (merge test-config-base
             {:perturbations   {:temperature {:spatial-type :pixel
                                              :range        [-1.0 1.0]}}
              :landfire-layers landfire-layers-weather-test
              :temperature     (:temperature weather-layers)})))

  (testing "wind-speed-20ft"
    (are [config] (valid-exits? (run-test-simulation! config))
      (merge test-config-base
             {:perturbations {:wind-speed-20ft {:spatial-type :global
                                                :range        [-1.0 1.0]}}})
      (merge test-config-base
             {:perturbations {:wind-speed-20ft {:spatial-type :pixel
                                                :range        [-1.0 1.0]}}})
      (merge test-config-base
             {:perturbations   {:wind-speed-20ft {:spatial-type :global
                                                  :range        [-1.0 1.0]}}
              :landfire-layers landfire-layers-weather-test
              :wind-speed-20ft (:wind-speed-20ft weather-layers)})

      (merge test-config-base
             {:perturbations   {:wind-speed-20ft {:spatial-type :pixel
                                                  :range        [-1.0 1.0]}}
              :landfire-layers landfire-layers-weather-test
              :wind-speed-20ft (:wind-speed-20ft weather-layers)})))

  (testing "fuel-moisture"
    (are [config] (valid-exits? (run-test-simulation! config))
      (merge test-config-base
             {:perturbations {:fuel-moisture-dead-1hr        {:spatial-type :global
                                                              :range        [-1.0 1.0]}
                              :fuel-moisture-dead-10hr       {:spatial-type :global
                                                              :range        [-1.0 1.0]}
                              :fuel-moisture-dead-100hr      {:spatial-type :global
                                                              :range        [-1.0 1.0]}
                              :fuel-moisture-live-herbaceous {:spatial-type :global
                                                              :range        [-1.0 1.0]}
                              :fuel-moisture-live-woody      {:spatial-type :global
                                                              :range        [-1.0 1.0]}}
              :fuel-moisture {:dead {:1hr   0.2
                                     :10hr  0.2
                                     :100hr 0.2}
                              :live {:herbaceous 0.3
                                     :woody      0.6}}})
      (merge test-config-base
             {:perturbations   {:fuel-moisture-dead-1hr        {:spatial-type :global
                                                                :range        [-1.0 1.0]}
                                :fuel-moisture-dead-10hr       {:spatial-type :global
                                                                :range        [-1.0 1.0]}
                                :fuel-moisture-dead-100hr      {:spatial-type :global
                                                                :range        [-1.0 1.0]}
                                :fuel-moisture-live-herbaceous {:spatial-type :global
                                                                :range        [-1.0 1.0]}
                                :fuel-moisture-live-woody      {:spatial-type :global
                                                                :range        [-1.0 1.0]}}
              :landfire-layers landfire-layers-weather-test
              :fuel-moisture   {:dead {:1hr   {:type   :geotiff
                                               :source (in-file-path "weather-test/m1_to_sample.tif")}
                                       :10hr  {:type   :geotiff
                                               :source (in-file-path "weather-test/m10_to_sample.tif")}
                                       :100hr {:type   :geotiff
                                               :source (in-file-path "weather-test/m100_to_sample.tif")}}
                                :live {:herbaceous 0.3
                                       :woody      0.6}}}))))

;;-----------------------------------------------------------------------------
;; Outputs
;;-----------------------------------------------------------------------------

(deftest ^:simulation binary-output-files-test
  (let [config         (merge test-config-base
                              {:output-binary?   true
                               :output-directory "test/output"})
        _              (run-test-simulation! config)
        binary-results (binary/read-matrices-as-binary (utils/out-file-path "toa_0001_00001.bin")
                                                       [:float :float :float :int])]
    (is (some? binary-results))))

;;-----------------------------------------------------------------------------
;; Ignition Mask
;;-----------------------------------------------------------------------------

(deftest ^:simulation igniton-mask-test
  (let [config (merge test-config-base
                      {:landfire-layers landfire-layers-weather-test
                       :ignition-row    nil
                       :ignition-col    nil
                       :random-ignition {:ignition-mask {:type   :geotiff
                                                         :source (in-file-path "weather-test/ignition_mask.tif")}
                                         :edge-buffer   9843.0}})]
    (is (valid-exits? (run-test-simulation! config)))))

;;-----------------------------------------------------------------------------
;; Crowning disabled
;;-----------------------------------------------------------------------------

(deftest ^:simulation crowning-disabled-test
  (let [config (merge test-config-base
                      {:crowning-disabled? true})]
    (is (valid-exits? (run-test-simulation! config)))))

;;-----------------------------------------------------------------------------
;; Moisture Rasters
;;-----------------------------------------------------------------------------

(deftest ^:simulation moisture-rasters-test
  (let [config (merge test-config-base
                      {:landfire-layers landfire-layers-weather-test
                       :ignition-row    nil
                       :ignition-col    nil
                       :random-ignition {:ignition-mask {:type   :geotiff
                                                         :source (in-file-path "weather-test/ignition_mask.tif")}
                                         :edge-buffer   9843.0}
                       :fuel-moisture   {:dead {:1hr   {:type   :geotiff
                                                        :source (in-file-path "weather-test/m1_to_sample.tif")}
                                                :10hr  {:type   :geotiff
                                                        :source (in-file-path "weather-test/m10_to_sample.tif")}
                                                :100hr {:type   :geotiff
                                                        :source (in-file-path "weather-test/m100_to_sample.tif")}}
                                         :live {:woody      {:type   :geotiff
                                                             :source (in-file-path "weather-test/mlw_to_sample.tif")}
                                                :herbaceous {:type   :geotiff
                                                             :source (in-file-path "weather-test/mlh_to_sample.tif")}}}})]
    (is (valid-exits? (run-test-simulation! config)))))

(deftest ^:simulation moisture-scalars-only-test
  (let [config (merge test-config-base
                      {:landfire-layers landfire-layers-weather-test
                       :ignition-row    nil
                       :ignition-col    nil
                       :random-ignition {:ignition-mask {:type   :geotiff
                                                         :source (in-file-path "weather-test/ignition_mask.tif")}
                                         :edge-buffer   9843.0}
                       :fuel-moisture   {:dead {:1hr   0.10
                                                :10hr  0.10
                                                :100hr 0.10}
                                         :live {:woody      0.80
                                                :herbaceous 0.80}}})]
    (is (valid-exits? (run-test-simulation! config)))))

(deftest ^:simulation moisture-mix-raster-scalars-test
  (let [config (merge test-config-base
                      {:landfire-layers landfire-layers-weather-test
                       :ignition-row    nil
                       :ignition-col    nil
                       :random-ignition {:ignition-mask {:type   :geotiff
                                                         :source (in-file-path "weather-test/ignition_mask.tif")}
                                         :edge-buffer   9843.0}
                       :fuel-moisture   {:dead {:1hr   {:type   :geotiff
                                                        :source (in-file-path "weather-test/m1_to_sample.tif")}
                                                :10hr  {:type   :geotiff
                                                        :source (in-file-path "weather-test/m10_to_sample.tif")}
                                                :100hr {:type   :geotiff
                                                        :source (in-file-path "weather-test/m100_to_sample.tif")}}
                                         :live {:woody      80.0
                                                :herbaceous 30.0}}})]
    (is (valid-exits? (run-test-simulation! config)))))

;;-----------------------------------------------------------------------------
;; Ignition CSV
;;-----------------------------------------------------------------------------

(deftest ^:simulation ignition-csv-test
  (let [results (run-test-simulation! (assoc test-config-base :ignition-csv (in-file-path "sample_ignitions.csv")))]

    (is (valid-exits? results))

    (is (= 3 (count results))
        "Should have the same number of simulations as ignition rows in sample_ignitions.csv")

    (is (= 10.0 (:global-clock (first results)))
        "Global clock should end at start_time + max_runtime in sample_ignitions.csv")

    (is (= 20.0 (:global-clock (second results)))
        "Global clock should end at start_time + max_runtime in sample_ignitions.csv")))


;;-----------------------------------------------------------------------------
;; Pyrome spread rate adjustment
;;-----------------------------------------------------------------------------

(deftest ^:simulation spread-rate-adjustment-test
  (testing "successful run using explicitly defined spread-rate-adjustments for each simulation in the ensemble run "
    (let [config (merge test-config-base
                        {:fuel-number->spread-rate-adjustment-samples [{144 0.5
                                                                        148 0.5
                                                                        164 0.5
                                                                        184 0.5
                                                                        188 0.5
                                                                        102 0.5
                                                                        204 0.5
                                                                        104 0.5
                                                                        106 0.5
                                                                        108 0.5
                                                                        122 0.5
                                                                        124 0.5
                                                                        141 0.5
                                                                        145 0.5
                                                                        149 0.5
                                                                        161 0.5
                                                                        165 0.5
                                                                        181 0.5
                                                                        185 0.5
                                                                        189 0.5
                                                                        201 0.5
                                                                        142 0.5
                                                                        146 0.5
                                                                        162 0.5
                                                                        182 0.5
                                                                        186 0.5
                                                                        101 0.5
                                                                        202 0.5
                                                                        103 0.5
                                                                        105 0.5
                                                                        107 0.5
                                                                        109 0.5
                                                                        121 0.5
                                                                        123 0.5
                                                                        143 0.5
                                                                        147 0.5
                                                                        163 0.5
                                                                        183 0.5
                                                                        187 0.5
                                                                        203 0.5}]})]

      (is (valid-exits? (run-test-simulation! config))))))

(deftest ^:simulation spread-rate-adjustment-for-fuel-model-test
  (testing "successful run using explicitly defined sdi suppresion constants for each simulation in the ensemble run"
    (let [config (merge test-config-base
                        {:suppression                                           {:sdi-layer      {:type   :geotiff
                                                                                                  :source "test/gridfire/resources/sdi.tif"}
                                                                                 :suppression-dt 10}
                         :sdi-sensitivity-to-difficulty-samples                 [1.0]
                         :sdi-containment-overwhelming-area-growth-rate-samples [50000.0]
                         :sdi-reference-suppression-speed-samples               [600.0]})]

      (is (valid-exits? (run-test-simulation! config))))))

(deftest ^:simulation surface-fire-min-memoization-test
  (let [default-res (run-test-simulation! test-config-base)]
    (testing (str (pr-str '{:memoization {:surface-fire-min XXX}}) " changes how surface-fire-min cacheing happens, with possible values:")
      (testing (str (pr-str :across-sims) "(default): one cache per simulation.")
        (let [config (assoc test-config-base :memoization {:surface-fire-min :across-sims})
              res    (run-test-simulation! config)]
          (is (valid-exits? res))
          (is (= (results-signature default-res)
                 (results-signature res))
              "the logical behavior of the simulation is unchanged.")))
      (testing (str (pr-str :within-sims) ": one cache per simulation.")
        (let [config (assoc test-config-base :memoization {:surface-fire-min :within-sims})
              res    (run-test-simulation! config)]
          (is (valid-exits? res))
          (is (= (results-signature default-res)
                 (results-signature res))
              "the logical behavior of the simulation is unchanged.")))
      (testing (str (pr-str nil) ": no memoization.")
        (let [config (assoc test-config-base :memoization {:surface-fire-min nil})
              res    (run-test-simulation! config)]
          (is (valid-exits? res))
          (is (= (results-signature default-res)
                 (results-signature res))
              "the logical behavior of the simulation is unchanged."))))))
;; gridfire.core-test ends here
