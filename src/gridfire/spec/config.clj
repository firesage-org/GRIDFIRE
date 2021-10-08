(ns gridfire.spec.config
  (:require [clojure.spec.alpha            :as s]
            [gridfire.spec.common          :as common]
            [gridfire.spec.fuel-moisture   :as fuel-moisture]
            [gridfire.spec.ignition        :as ignition]
            [gridfire.spec.output          :as output]
            [gridfire.spec.optimization    :as optimization]
            [gridfire.spec.perturbations   :as perturbations]
            [gridfire.spec.random-ignition :as random-ignition]
            [gridfire.spec.spotting        :as spotting]))

;;-----------------------------------------------------------------------------
;; Weather Layers ;;TODO move into own namespace
;;-----------------------------------------------------------------------------

(s/def ::weather
  (s/or :vector (s/coll-of int? :kind vector? :count 2)
        :list (s/coll-of int? :kind list?)
        :string string?
        :scalar (s/or :int int?
                      :float float?)
        :map ::common/postgis-or-geotiff))

(s/def ::temperature ::weather)
(s/def ::relative-humidity ::weather)
(s/def ::wind-speed-20ft ::weather)
(s/def ::wind-from-direction ::weather)

(s/def ::weather-layers
  (s/keys
   :req-un [::temperature ::relative-humidity ::wind-speed-20ft ::wind-from-direction]))

;;-----------------------------------------------------------------------------
;; Landfire Layers ;;TODO move into own namespace
;;-----------------------------------------------------------------------------

(s/def ::path-or-map (s/or :path ::common/path
                           :map  ::common/postgis-or-geotiff))
(s/def ::aspect ::path-or-map)
(s/def ::canopy-base-height ::path-or-map)
(s/def ::canopy-cover ::path-or-map)
(s/def ::canopy-height ::path-or-map)
(s/def ::crown-bulk-density ::path-or-map)
(s/def ::elevation ::path-or-map)
(s/def ::fuel-model ::path-or-map)
(s/def ::slope ::path-or-map)
(s/def ::cell-size float?)

(s/def ::landfire-layers
  (s/keys
   :req-un [::aspect
            ::canopy-base-height
            ::canopy-cover
            ::canopy-height
            ::crown-bulk-density
            ::elevation
            ::fuel-model
            ::slope]))

;;-----------------------------------------------------------------------------
;; Fractional Distance Algorithm
;;-----------------------------------------------------------------------------

(s/def ::fractional-distance-combination #{:sum})

;;-----------------------------------------------------------------------------
;; Config
;;-----------------------------------------------------------------------------


(s/def ::config
  (s/and
   (s/keys
    :req-un [::cell-size
             ::landfire-layers]
    :opt-un [::fuel-moisture/fuel-moisture-layers
             ::ignition/ignition-layer
             ::ignition/ignitions-csv
             ::optimization/parallel-strategy
             ::output/output-binary?
             ::output/output-burn-probability
             ::output/output-layers
             ::perturbations/perturbations
             ::random-ignition/random-ignition
             ::spotting/spotting])
   ::weather-layers))
