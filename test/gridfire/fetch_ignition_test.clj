;; [[file:../../org/GridFire.org::gridfire.fetch-ignition-test][gridfire.fetch-ignition-test]]
(ns gridfire.fetch-ignition-test
  (:require [clojure.test                :refer [deftest is testing use-fixtures]]
            [gridfire.fetch              :as fetch]
            [gridfire.utils.test         :as utils]
            [tech.v3.datatype.functional :as dfn]))

;;-----------------------------------------------------------------------------
;; Config
;;-----------------------------------------------------------------------------

(def resources-path "test/gridfire/resources/")

(def db-spec {:classname   "org.postgresql.Driver"
              :subprotocol "postgresql"
              :subname     "//localhost:5432/gridfire_test"
              :user        "gridfire_test"
              :password    "gridfire_test"})

(def test-config-base
  {:db-spec                   db-spec
   :landfire-layers           {:aspect             {:type   :geotiff
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
   :cell-size                 98.425     ; (feet)
   :max-runtime               60         ; (minutes)
   :temperature               '(50)      ; (degrees Fahrenheit)
   :relative-humidity         '(1)       ; (%)
   :wind-speed-20ft           '(10)      ; (miles/hour)
   :wind-from-direction       '(0)       ; (degrees clockwise from north)
   :foliar-moisture           90         ; (%)
   :ellipse-adjustment-factor 1.0        ; (< 1.0 = more circular, > 1.0 = more elliptical)
   :simulations               1
   :random-seed               1234567890 ; long value (optional)
   :output-csvs?              true})

;;-----------------------------------------------------------------------------
;; Utils
;;-----------------------------------------------------------------------------

(defn in-file-path [filename]
  (str resources-path filename))

;;-----------------------------------------------------------------------------
;; Fixtures
;;-----------------------------------------------------------------------------

(use-fixtures :once utils/with-reset-db-pool)

;;-----------------------------------------------------------------------------
;; Tests
;;-----------------------------------------------------------------------------

(deftest ^:database fetch-ignition-layer-test
  (testing "Fetching ignition layer from postgis and geotiff file"
    (let [geotiff-config         (merge test-config-base
                                        {:ignition-layer {:type   :geotiff
                                                          :source (in-file-path "ign.tif")}})
          postgis-config         (merge test-config-base
                                        {:ignition-layer {:type   :postgis
                                                          :source "ignition.ign WHERE rid=1"}})
          geotiff-ignition-layer (fetch/ignition-layer postgis-config)
          postgis-ignition-layer (fetch/ignition-layer geotiff-config)]

      (is (dfn/equals (:matrix geotiff-ignition-layer)
                   (:matrix postgis-ignition-layer))))))
;; gridfire.fetch-ignition-test ends here
