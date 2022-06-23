;; [[file:../../org/GridFire.org::fire-spread-algorithm][fire-spread-algorithm]]
(ns gridfire.fire-spread
  (:require [clojure.core.reducers         :as r]
            [gridfire.common               :refer [burnable-fuel-model?
                                                   burnable?
                                                   calc-fuel-moisture
                                                   in-bounds?
                                                   burnable-neighbors?
                                                   get-neighbors
                                                   distance-3d
                                                   non-zero-indices]]
            [gridfire.conversion          :refer [mph->fpm]]
            [gridfire.crown-fire          :refer [crown-fire-eccentricity
                                                  crown-fire-line-intensity
                                                  cruz-crown-fire-spread
                                                  van-wagner-crown-fire-initiation?]]
            [gridfire.fire-spread-optimal :refer [rothermel-fast-wrapper-optimal]]
            [gridfire.fuel-models         :refer [build-fuel-model moisturize]]
            [gridfire.spotting            :as spot]
            [gridfire.surface-fire        :refer [anderson-flame-depth
                                                  byram-fire-line-intensity
                                                  byram-flame-length
                                                  rothermel-surface-fire-spread-any
                                                  rothermel-surface-fire-spread-max
                                                  rothermel-surface-fire-spread-no-wind-no-slope
                                                  wind-adjustment-factor]]
            [gridfire.fuel-models-optimal  :as f-opt]
            [gridfire.surface-fire-optimal :as s-opt]
            [tech.v3.datatype             :as d]
            [tech.v3.datatype.functional  :as dfn]
            [tech.v3.tensor               :as t]
            [taoensso.tufte :as tufte]))

;; for surface fire, tau = 10 mins, t0 = 0, and t = global-clock
;; for crown fire, tau = 20 mins, t0 = time of first torch, t = global-clock
;; (defn lautenberger-spread-acceleration
;;   [equilibrium-spread-rate t0 t tau]
;;   (* equilibrium-spread-rate (- 1.0 (Math/exp (/ (- t0 t 0.2) tau)))))
;;
;; Note: Because of our use of adaptive timesteps, if the spread rate on
;;       the first timestep is not at least 83 ft/min, then the timestep will
;;       be calculated as greater than 60 minutes, which will terminate the
;;       one hour fire simulation instantly.

(defn random-cell
  "Returns a random [i j] pair with i < num-rows and j < num-cols."
  [num-rows num-cols]
  [(rand-int num-rows)
   (rand-int num-cols)])

(def offset-to-degrees
  "Returns clockwise degrees from north."
  {[-1  0] 0.0   ; N
   [-1  1] 45.0  ; NE
   [ 0  1] 90.0  ; E
   [ 1  1] 135.0 ; SE
   [ 1  0] 180.0 ; S
   [ 1 -1] 225.0 ; SW
   [ 0 -1] 270.0 ; W
   [-1 -1] 315.0}) ; NW

(defn rothermel-fast-wrapper
  [fuel-model-number fuel-moisture grass-suppression?]
  (let [fuel-model      (-> (build-fuel-model (int fuel-model-number))
                            (moisturize fuel-moisture))
        spread-info-min (rothermel-surface-fire-spread-no-wind-no-slope fuel-model grass-suppression?)]
    [fuel-model spread-info-min]))

(defrecord BurnTrajectory
    [cell
     source
     trajectory ;;=> use integer, bit, or angle
     ^double terrain-distance
     ^double spread-rate
     ^double fire-line-intensity
     ^double flame-length
     fractional-distance
     fire-type
     crown-fire?])

(defn compute-burn-trajectory
  [neighbor here surface-fire-min surface-fire-max crown-bulk-density
   canopy-cover canopy-height canopy-base-height foliar-moisture crown-spread-max
   crown-eccentricity elevation-matrix cell-size overflow-trajectory overflow-heat
   crown-type]
  (let [trajectory                (mapv - neighbor here)
        spread-direction          (offset-to-degrees trajectory)
        surface-spread-rate       (rothermel-surface-fire-spread-any surface-fire-max
                                                                     spread-direction)
        residence-time            (:residence-time surface-fire-min)
        reaction-intensity        (:reaction-intensity surface-fire-min)
        surface-intensity         (->> (anderson-flame-depth surface-spread-rate residence-time)
                                       (byram-fire-line-intensity reaction-intensity))
        crown-fire?               (van-wagner-crown-fire-initiation? canopy-cover
                                                                     canopy-base-height
                                                                     foliar-moisture
                                                                     surface-intensity)
        ^double crown-spread-rate (when crown-fire?
                                    (rothermel-surface-fire-spread-any
                                     (assoc surface-fire-max
                                            :max-spread-rate crown-spread-max
                                            :eccentricity crown-eccentricity)
                                     spread-direction))
        ^double crown-intensity   (when crown-fire?
                                    (crown-fire-line-intensity crown-spread-rate
                                                               crown-bulk-density
                                                               (- canopy-height canopy-base-height)
                                                               (:heat-of-combustion surface-fire-min))) ; 0 = dead-1hr
        spread-rate               (if crown-fire?
                                    (max surface-spread-rate crown-spread-rate)
                                    surface-spread-rate)
        fire-line-intensity       (if crown-fire?
                                    (+ surface-intensity crown-intensity)
                                    surface-intensity)
        flame-length              (byram-flame-length fire-line-intensity)]
    (->BurnTrajectory neighbor
                      here
                      trajectory
                      (distance-3d elevation-matrix cell-size here neighbor)
                      spread-rate
                      fire-line-intensity
                      flame-length
                      (volatile! (if (= trajectory overflow-trajectory)
                                   overflow-heat
                                   0.0))
                      (if crown-fire? crown-type :surface)
                      crown-fire?)))

;;TODO Optimize me!
(defn compute-neighborhood-fire-spread-rates!
  "Returns a vector of entries of the form:
  {:cell [i j],
   :trajectory [di dj],
   :terrain-distance ft,
   :spread-rate ft/min,
   :fire-line-intensity Btu/ft/s,
   :flame-length ft,
   :fractional-distance [0-1]}, one for each cell adjacent to here."
  [{:keys
    [get-aspect get-canopy-base-height get-canopy-cover get-canopy-height get-crown-bulk-density
     get-fuel-model get-slope elevation-matrix fuel-model-matrix get-wind-speed-20ft
     get-wind-from-direction get-temperature get-relative-humidity get-foliar-moisture
     ellipse-adjustment-factor cell-size num-rows num-cols get-fuel-moisture-dead-1hr
     get-fuel-moisture-dead-10hr get-fuel-moisture-dead-100hr get-fuel-moisture-live-herbaceous
     get-fuel-moisture-live-woody grass-suppression?]}
   fire-spread-matrix
   [i j :as here]
   overflow-trajectory
   overflow-heat
   global-clock]
  (let [band                                        (int (/ global-clock 60.0))
        ^double aspect                              (get-aspect i j)
        ^double canopy-base-height                  (get-canopy-base-height i j)
        ^double canopy-height                       (get-canopy-height i j)
        ^double canopy-cover                        (get-canopy-cover i j)
        ^double crown-bulk-density                  (get-crown-bulk-density i j)
        ^double fuel-model                          (get-fuel-model i j)
        ^double slope                               (get-slope i j)
        ^double relative-humidity                   (get-relative-humidity band i j)
        ^double temperature                         (get-temperature band i j)
        ^double wind-speed-20ft                     (get-wind-speed-20ft band i j)
        ^double wind-from-direction                 (get-wind-from-direction band i j)
        ^double fuel-moisture-dead-1hr              (if get-fuel-moisture-dead-1hr
                                                      (get-fuel-moisture-dead-1hr band i j)
                                                      (calc-fuel-moisture relative-humidity temperature :dead :1hr))
        ^double fuel-moisture-dead-10hr             (if get-fuel-moisture-dead-10hr
                                                      (get-fuel-moisture-dead-10hr band i j)
                                                      (calc-fuel-moisture relative-humidity temperature :dead :10hr))
        ^double fuel-moisture-dead-100hr            (if get-fuel-moisture-dead-100hr
                                                      (get-fuel-moisture-dead-100hr band i j)
                                                      (calc-fuel-moisture relative-humidity temperature :dead :100hr))
        ^double fuel-moisture-live-herbaceous       (if get-fuel-moisture-live-herbaceous
                                                      (get-fuel-moisture-live-herbaceous i j)
                                                      (calc-fuel-moisture relative-humidity temperature :live :herbaceous))
        ^double fuel-moisture-live-woody            (if get-fuel-moisture-live-woody
                                                      (get-fuel-moisture-live-woody i j)
                                                      (calc-fuel-moisture relative-humidity temperature :live :woody))
        ^double foliar-moisture                     (get-foliar-moisture band i j)
        surface-fire-min                            (rothermel-fast-wrapper-optimal
                                                     fuel-model
                                                     [fuel-moisture-dead-1hr
                                                      fuel-moisture-dead-10hr
                                                      fuel-moisture-dead-100hr
                                                      0.0 ; fuel-moisture-dead-herbaceous
                                                      fuel-moisture-live-herbaceous
                                                      fuel-moisture-live-woody]
                                                     grass-suppression?)
        midflame-wind-speed                         (mph->fpm
                                                     (* wind-speed-20ft
                                                        (wind-adjustment-factor (:fuel-bed-depth surface-fire-min)
                                                                                canopy-height
                                                                                canopy-cover)))
        surface-fire-max                            (rothermel-surface-fire-spread-max surface-fire-min
                                                                                       midflame-wind-speed
                                                                                       wind-from-direction
                                                                                       slope
                                                                                       aspect
                                                                                       ellipse-adjustment-factor)
        crown-spread-max                            (cruz-crown-fire-spread wind-speed-20ft crown-bulk-density fuel-moisture-dead-1hr)
        crown-type                                  (if (neg? crown-spread-max) :passive-crown :active-crown)
        crown-spread-max                            (Math/abs crown-spread-max)
        crown-eccentricity                          (crown-fire-eccentricity wind-speed-20ft
                                                                             ellipse-adjustment-factor)]
    (into []
          (comp
           (filter #(and (in-bounds? num-rows num-cols %)
                         (burnable? fire-spread-matrix fuel-model-matrix here %)))
           (map #(compute-burn-trajectory % here surface-fire-min surface-fire-max
                                          crown-bulk-density canopy-cover canopy-height
                                          canopy-base-height foliar-moisture crown-spread-max
                                          crown-eccentricity elevation-matrix cell-size
                                          overflow-trajectory overflow-heat crown-type)))
          (get-neighbors here))))

(defn- get-old-fractional-distance
  [{:keys [trajectory-combination]} {:keys [fractional-distance]} fractional-distance-matrix [i j]]
  (if (= trajectory-combination :sum)
    (t/mget fractional-distance-matrix i j)
    @fractional-distance))

(defn- update-fractional-distance-matrix!
  "Update the fractional distance matrix with the largest fractional distance calculated."
  [fractional-distance-matrix max-fractionals]
  (doseq [[cell fractional-distance] @max-fractionals]
    (let [[i j] cell]
      (t/mset! fractional-distance-matrix i j fractional-distance))))

(defn- update-fractional-distance!
  "Update fractional distance for given trajectory into the current cell. Return a tuple of [old-value new-value]"
  [{:keys [trajectory-combination] :as inputs} max-fractionals trajectory fractional-distance-matrix timestep cell]
  (let [terrain-distance    (double (:terrain-distance trajectory))
        spread-rate         (double (:spread-rate trajectory)) ;TODO recompute spread rates when crossing hourly boundary
        new-spread-fraction (/ (* spread-rate timestep) terrain-distance)
        old-total           (get-old-fractional-distance inputs trajectory fractional-distance-matrix cell)
        new-total           (+ old-total new-spread-fraction)]
    (if (= trajectory-combination :sum)
      (let [max-fractional-distance (max (get @max-fractionals cell 0.0) new-total)]
        (swap! max-fractionals assoc cell max-fractional-distance))
      (vreset! (:fractional-distance trajectory) new-total))
    [old-total new-total]))

(defn- update-overflow-heat
  [{:keys [num-rows num-cols]} fractional-distance-matrix {:keys [cell trajectory]} fractional-distance]
  (let [[i j :as target] (mapv + cell trajectory)]
    (when (in-bounds? num-rows num-cols target)
      (t/mset! fractional-distance-matrix i j (- fractional-distance 1.0)))))

(defn ignition-event-reducer
  [inputs max-fractionals fractional-distance-matrix timestep trajectory-combination fire-spread-matrix
   acc trajectory]
  (let [{:keys [source cell]}                 trajectory ;TODO cell -> target
        [i j]                                 source
        [^double old-total ^double new-total] (update-fractional-distance! inputs
                                                                           max-fractionals
                                                                           trajectory
                                                                           fractional-distance-matrix
                                                                           timestep
                                                                           cell)]
    (if (and (>= new-total 1.0)
             (> new-total ^double (get-in acc [cell :fractional-distance] 0.0)))
      (do (when (and (= trajectory-combination :sum) (> new-total 1.0))
            (update-overflow-heat inputs fractional-distance-matrix trajectory new-total))
          (assoc! acc cell (merge trajectory {:fractional-distance  new-total
                                              :dt-adjusted          (* (/ (- 1.0 old-total) (- new-total old-total))
                                                                       timestep)
                                              :ignition-probability (t/mget fire-spread-matrix i j)})))
      acc)))

(defn find-new-ignitions ;51%
  [{:keys [trajectory-combination] :as inputs}
   {:keys [fire-spread-matrix fractional-distance-matrix]}
   burn-trajectories
   ^double timestep]
  (let [max-fractionals (atom {})
        reducer-fn      (fn [acc trajectory]
                          (ignition-event-reducer inputs max-fractionals fractional-distance-matrix
                                                  timestep trajectory-combination fire-spread-matrix
                                                  acc trajectory))
        ignition-events (->> burn-trajectories
                             (reduce reducer-fn (transient {}))
                             persistent!
                             vals)]
    (when (= trajectory-combination :sum)
      (update-fractional-distance-matrix! fractional-distance-matrix max-fractionals))
    ignition-events))

;; Tufte 31%
(defn update-burn-trajectories
  [{:keys [fuel-model-matrix num-rows num-cols parallel-strategy] :as constants}
   burn-trajectories
   ignition-events
   fire-spread-matrix
   global-clock]
  (let [parallel-bin-size        (max 1 (quot (count ignition-events) (.availableProcessors (Runtime/getRuntime))))
        newly-burn-trajectories  (into #{} (map :cell) ignition-events)
        pruned-burn-trajectories (into [] (remove #(contains? newly-burn-trajectories (:cell %))) burn-trajectories)
        reducer-fn               (if (= parallel-strategy :within-fires)
                                   #(->> (r/fold parallel-bin-size r/cat r/append! %)
                                         (reduce (fn [acc v] (into acc v)) pruned-burn-trajectories))
                                   #(reduce (fn [acc v] (into acc v)) pruned-burn-trajectories %))]
    (->> ignition-events
         (r/map (fn [{:keys [cell trajectory fractional-distance]}]
                  (let [fractional-distance (double fractional-distance)]
                    (when (burnable-neighbors? fire-spread-matrix
                                               fuel-model-matrix
                                               num-rows num-cols
                                               cell)
                      (compute-neighborhood-fire-spread-rates!
                       constants
                       fire-spread-matrix
                       cell
                       trajectory
                       (- fractional-distance 1.0)
                       global-clock)))))
         (r/remove nil?)
         (reducer-fn))))

(defn generate-burn-trajectories
  [inputs fire-spread-matrix cells]
  (reduce (fn [burn-trajectories cell]
            (into burn-trajectories
                  (compute-neighborhood-fire-spread-rates! inputs
                                                           fire-spread-matrix
                                                           cell
                                                           nil
                                                           0.0
                                                           0.0)))
          []
          cells))

(defn identify-spot-ignition-events
  [global-clock spot-ignitions]
  (let [to-ignite-now (group-by (fn [[_ [time _]]]
                                  (let [time (double time)]
                                    (>= ^double global-clock time)))
                                spot-ignitions)
        ignite-later  (into {} (get to-ignite-now false))
        ignite-now    (into {} (get to-ignite-now true))]
    [ignite-later ignite-now]))

(defn spot-burn-trajectories
  "Updates matrices for spot ignited cells
  Returns a map of ignited cells"
  [constants
   global-clock
   {:keys [fire-spread-matrix burn-time-matrix spread-rate-matrix fire-type-matrix
           flame-length-matrix fire-line-intensity-matrix spot-matrix]}
   spot-ignite-now]
  (let [ignited?          (fn [[k v]]
                            (let [[i j] k
                                  [_ p] v]
                              (> ^double (t/mget fire-spread-matrix i j) ^double p)))
        spot-ignite-now   (remove ignited? spot-ignite-now)
        burn-trajectories (generate-burn-trajectories constants
                                                      fire-spread-matrix
                                                      (keys spot-ignite-now))]
    (doseq [cell spot-ignite-now
            :let [[i j]                    (key cell)
                  [_ ignition-probability] (val cell)]]
      (t/mset! fire-spread-matrix i j ignition-probability)
      (t/mset! burn-time-matrix i j global-clock)
      (t/mset! flame-length-matrix i j 1.0)
      (t/mset! fire-line-intensity-matrix i j 1.0)
      (t/mset! spread-rate-matrix i j -1.0)
      (t/mset! fire-type-matrix i j -1.0)
      (t/mset! spot-matrix i j 1.0))
    burn-trajectories))

(defn new-spot-ignitions
  "Returns a map of [x y] locations to [t p] where:
  t: time of ignition
  p: ignition-probability"
  [{:keys [spotting] :as inputs} matrices ignition-events global-clock]
  (when spotting
    (reduce (fn [acc ignition-event]
              (merge-with (partial min-key first)
                          acc
                          (->> (spot/spread-firebrands
                                inputs
                                matrices
                                ignition-event
                                global-clock)
                               (into {}))))
            {}
            ignition-events)))

(def fire-type-to-value
  {:surface       1.0
   :passive-crown 2.0
   :active-crown  3.0})

(defn- find-max-spread-rate ^double
  [^double max-spread-rate ^BurnTrajectory burn-trajectory]
  (Math/max max-spread-rate ^double (:spread-rate burn-trajectory)))

(defn- compute-dt ^double
  [^double cell-size burn-trajectories]
  (if (seq burn-trajectories)
    (let [max-spread-rate (double (reduce find-max-spread-rate 0.0 burn-trajectories))]
      (/ cell-size max-spread-rate))
    10.0))

(defn- compute-spot-trajectories
  [inputs matrices global-clock ignition-events spot-ignitions]
  (let [new-spot-ignitions     (new-spot-ignitions inputs ;TODO optimize
                                                   matrices
                                                   ignition-events
                                                   global-clock)
        [spot-ignite-later
         spot-ignite-now]      (identify-spot-ignition-events global-clock ;TODO optimize
                                                              (merge-with (partial min-key first)
                                                                          spot-ignitions
                                                                          new-spot-ignitions))
        spot-burn-trajectories (spot-burn-trajectories inputs ;TODO optimize
                                                       global-clock
                                                       matrices
                                                       spot-ignite-now)]
    [spot-ignite-later spot-burn-trajectories]))

(defn- store-ignition-events!
  [{:keys [fire-spread-matrix flame-length-matrix fire-line-intensity-matrix burn-time-matrix
           spread-rate-matrix fire-type-matrix]}
   global-clock
   ignition-events]
  (doseq [{:keys
           [cell flame-length fire-line-intensity
            ignition-probability spread-rate fire-type
            dt-adjusted]} ignition-events] ;TODO investigate using records for ignition-events
    (let [[i j] cell]
      (t/mset! fire-spread-matrix         i j ignition-probability)
      (t/mset! flame-length-matrix        i j flame-length)
      (t/mset! fire-line-intensity-matrix i j fire-line-intensity)
      (t/mset! burn-time-matrix           i j (+ global-clock ^double dt-adjusted))
      (t/mset! spread-rate-matrix         i j spread-rate)
      (t/mset! fire-type-matrix           i j (fire-type fire-type-to-value))))) ;TODO Use number

(defn run-loop
  [{:keys [max-runtime cell-size ignition-start-time] :as inputs}
   {:keys
    [fire-spread-matrix flame-length-matrix fire-line-intensity-matrix burn-time-matrix
     spread-rate-matrix fire-type-matrix fractional-distance-matrix spot-matrix] :as matrices}
   ignited-cells]
  (let [max-runtime         (double max-runtime)
        cell-size           (double cell-size)
        ignition-start-time (double ignition-start-time)
        ignition-stop-time  (+ ignition-start-time max-runtime)]
    (loop [global-clock      ignition-start-time
           burn-trajectories (generate-burn-trajectories inputs fire-spread-matrix ignited-cells)
           spot-ignitions    {}
           spot-count        0
           crown-count       0]
      (if (and (< global-clock ignition-stop-time)
               (or (seq burn-trajectories) (seq spot-ignitions)))
        (let [timestep        (Math/min (compute-dt cell-size burn-trajectories)
                                        (- ignition-stop-time global-clock))
              ignition-events (find-new-ignitions inputs matrices burn-trajectories timestep)]
          (store-ignition-events! matrices global-clock ignition-events)
          (let [[spot-ignite-later
                 spot-burn-trajectories] (compute-spot-trajectories inputs matrices global-clock
                                                                    ignition-events spot-ignitions)]
            (recur (+ global-clock timestep)
                   (update-burn-trajectories inputs
                                             (into spot-burn-trajectories burn-trajectories)
                                             ignition-events
                                             fire-spread-matrix
                                             global-clock)
                   spot-ignite-later
                   (+ spot-count (count spot-burn-trajectories))
                   (+ crown-count (count (filterv :crown-fire? ignition-events))))))
        {:global-clock               global-clock
         :exit-condition             (if (>= global-clock ignition-stop-time) :max-runtime-reached :no-burnable-fuels)
         :fire-spread-matrix         fire-spread-matrix
         :flame-length-matrix        flame-length-matrix
         :fire-line-intensity-matrix fire-line-intensity-matrix
         :burn-time-matrix           burn-time-matrix
         :spot-matrix                spot-matrix
         :spread-rate-matrix         spread-rate-matrix
         :fire-type-matrix           fire-type-matrix
         :crown-fire-count           crown-count
         :spot-count                 spot-count}))))

(defmulti run-fire-spread
  "Runs the raster-based fire spread model with a map of these arguments:
  - max-runtime: double (minutes)
  - cell-size: double (feet)
  - elevation-matrix: core.matrix 2D double array (feet)
  - slope-matrix: core.matrix 2D double array (vertical feet/horizontal feet)
  - aspect-matrix: core.matrix 2D double array (degrees clockwise from north)
  - fuel-model-matrix: core.matrix 2D double array (fuel model numbers 1-256)
  - canopy-height-matrix: core.matrix 2D double array (feet)
  - canopy-base-height-matrix: core.matrix 2D double array (feet)
  - crown-bulk-density-matrix: core.matrix 2D double array (lb/ft^3)
  - canopy-cover-matrix: core.matrix 2D double array (0-100)
  - wind-speed-20ft: double (miles/hour)
  - wind-from-direction: double (degrees clockwise from north)
  - fuel-moisture: doubles (0-1) {:dead {:1hr :10hr :100hr} :live {:herbaceous :woody}}
  - foliar-moisture: double (0-1)
  - ellipse-adjustment-factor: (< 1.0 = more circular, > 1.0 = more elliptical)
  - initial-ignition-site: One of the following:
     - point represented as [row col]
     - a core.matrix 2D double array (0-2)
  - num-rows: integer
  - num-cols: integer"
  (fn [{:keys [initial-ignition-site]}]
    (if (vector? initial-ignition-site)
      :ignition-point
      :ignition-perimeter)))

;;-----------------------------------------------------------------------------
;; Igniiton Point
;;-----------------------------------------------------------------------------

(defn- initialize-point-ignition-matrices
  [{:keys [num-rows num-cols initial-ignition-site ignition-start-time spotting trajectory-combination]}]
  (let [[i j]                      initial-ignition-site
        shape                      [num-rows num-cols]
        burn-time-matrix           (t/new-tensor shape)
        fire-line-intensity-matrix (t/new-tensor shape)
        fire-spread-matrix         (t/new-tensor shape)
        fire-type-matrix           (t/new-tensor shape)
        firebrand-count-matrix     (when spotting (t/new-tensor shape))
        flame-length-matrix        (t/new-tensor shape)
        fractional-distance-matrix (when (= trajectory-combination :sum) (t/new-tensor shape))
        spot-matrix                (t/new-tensor shape) ;;TODO check if spot-matrix requires spotting
        spread-rate-matrix         (t/new-tensor shape)]
    (t/mset! burn-time-matrix i j ignition-start-time)
    (t/mset! fire-line-intensity-matrix i j 1.0)       ;TODO should this be zero?
    (t/mset! fire-spread-matrix i j 1.0)
    (t/mset! fire-type-matrix i j -1.0)                ;TODO should this be zero?
    (t/mset! flame-length-matrix i j 1.0)              ;TODO should this be zero?
    (t/mset! spread-rate-matrix i j -1.0)              ;TODO should this be zero?
    {:burn-time-matrix           burn-time-matrix
     :fire-line-intensity-matrix fire-line-intensity-matrix
     :fire-spread-matrix         fire-spread-matrix
     :fire-type-matrix           fire-type-matrix
     :firebrand-count-matrix     firebrand-count-matrix
     :flame-length-matrix        flame-length-matrix
     :fractional-distance-matrix fractional-distance-matrix
     :spot-matrix                spot-matrix
     :spread-rate-matrix         spread-rate-matrix}))

(defmethod run-fire-spread :ignition-point
  [{:keys [initial-ignition-site] :as inputs}]
  (run-loop inputs (initialize-point-ignition-matrices inputs) [initial-ignition-site]))

;;-----------------------------------------------------------------------------
;; Ignition Perimeter
;;-----------------------------------------------------------------------------

(defn- initialize-perimeter-ignition-matrices
  [{:keys [num-rows num-cols spotting trajectory-combination initial-ignition-site]}]
  (let [shape              [num-rows num-cols]
        positive-burn-scar initial-ignition-site
        negative-burn-scar (d/clone (dfn/* -1.0 positive-burn-scar))]
    {:burn-time-matrix           negative-burn-scar
     :fire-line-intensity-matrix (d/clone negative-burn-scar)
     :fire-spread-matrix         (d/clone positive-burn-scar)
     :fire-type-matrix           (d/clone negative-burn-scar)
     :firebrand-count-matrix     (when spotting (t/new-tensor shape))
     :flame-length-matrix        (d/clone negative-burn-scar)
     :fractional-distance-matrix (when (= trajectory-combination :sum) (d/clone positive-burn-scar))
     :spot-matrix                (t/new-tensor shape) ;TODO check if spot-matrix requires spotting
     :spread-rate-matrix         (d/clone negative-burn-scar)}))

(defn- get-non-zero-indices [m]
  (let [{:keys [row-idxs col-idxs]} (non-zero-indices m)]
    (map vector row-idxs col-idxs)))

(defmethod run-fire-spread :ignition-perimeter
  [{:keys [num-rows num-cols initial-ignition-site fuel-model-matrix] :as inputs}]
  (when-let [ignited-cells (->> (get-non-zero-indices initial-ignition-site)
                                  (filter #(burnable-neighbors? initial-ignition-site
                                                                fuel-model-matrix
                                                                num-rows
                                                                num-cols
                                                                %))
                                  seq)]
    (run-loop inputs (initialize-perimeter-ignition-matrices inputs) ignited-cells)))
;; fire-spread-algorithm ends here

(comment

  ;; Problem 1: Efficiently store, update, and query the LOT

  ;; Solution: Store the LOT in a tensor for 23-56x speedups over the alternatives

  (def lot (t/new-tensor [10 10] :datatype :byte))

  (defn get-n-s? [i j]
    (-> (t/mget lot i j)
        (bit-test 3)))

  (defn get-ne-sw? [i j]
    (-> (t/mget lot i j)
        (bit-test 2)))

  (defn get-e-w? [i j]
    (-> (t/mget lot i j)
        (bit-test 1)))

  (defn get-se-nw? [i j]
    (-> (t/mget lot i j)
        (bit-test 0)))

  (defn set-n-s! [i j]
    (as-> (t/mget lot i j) %
      (bit-set % 3)
      (t/mset! lot i j %)))

  (defn set-ne-sw! [i j]
    (as-> (t/mget lot i j) %
      (bit-set % 2)
      (t/mset! lot i j %)))

  (defn set-e-w! [i j]
    (as-> (t/mget lot i j) %
      (bit-set % 1)
      (t/mset! lot i j %)))

  (defn set-se-nw! [i j]
    (as-> (t/mget lot i j) %
      (bit-set % 0)
      (t/mset! lot i j %)))

  ;; Problem 2: Efficiently update burn trajectories

  ;; Solution: Use records. They are ~10% slower than maps to create
  ;; but they are 2x as fast for querying and updating. Also, prefer
  ;; to create new records whenever possible because it is 25% faster
  ;; to create a record than to update it.

  (defn make-burn-trajectory-map
    [i j direction spread-rate terrain-distance fractional-distance burn-probability]
    {:i                   i
     :j                   j
     :direction           direction
     :spread-rate         spread-rate
     :terrain-distance    terrain-distance
     :fractional-distance fractional-distance
     :burn-probability    burn-probability})

  (defrecord BurnTrajectoryRecord
      [^long  i
       ^long  j
       ^byte  direction
       ^double spread-rate
       ^double terrain-distance
       ^double fractional-distance
       ^double burn-probability])

  (defn make-burn-trajectory-record
    [i j direction spread-rate terrain-distance fractional-distance burn-probability]
    (->BurnTrajectoryRecord i j direction spread-rate terrain-distance fractional-distance burn-probability))

  (definterface IBurnTrajectory
    (^long getI [])
    (^long getJ [])
    (^byte getDirection [])
    (^double getSpreadRate [])
    (^double getTerrainDistance [])
    (^double getFractionalDistance [])
    (^double getBurnProbability [])
    (^void setI [^long i])
    (^void setJ [^long j])
    (^void setDirection [^byte direction])
    (^void setSpreadRate [^double spreadRate])
    (^void setTerrainDistance [^double terrainDistance])
    (^void setFractionalDistance [^double fractionalDistance])
    (^void setBurnProbability [^double burnProbability]))

  (deftype ImmutableBurnTrajectory
      [^long  i
       ^long  j
       ^byte  direction
       ^double spreadRate
       ^double terrainDistance
       ^double fractionalDistance
       ^double burnProbability]
      IBurnTrajectory
      (^long getI [this] i)
      (^long getJ [this] j)
      (^byte getDirection [this] direction)
      (^double getSpreadRate [this] spreadRate)
      (^double getTerrainDistance [this] terrainDistance)
      (^double getFractionalDistance [this] fractionalDistance)
      (^double getBurnProbability [this] burnProbability))

  (defn make-burn-trajectory-itype
    [i j direction spread-rate terrain-distance fractional-distance burn-probability]
    (->ImmutableBurnTrajectory i j direction spread-rate terrain-distance fractional-distance burn-probability))

  (deftype VolatileMutableBurnTrajectory
      [^:volatile-mutable ^long  i
       ^:volatile-mutable ^long  j
       ^:volatile-mutable ^byte  direction
       ^:volatile-mutable ^double spreadRate
       ^:volatile-mutable ^double terrainDistance
       ^:volatile-mutable ^double fractionalDistance
       ^:volatile-mutable ^double burnProbability]
      IBurnTrajectory
      (^long getI [this] i)
      (^long getJ [this] j)
      (^byte getDirection [this] direction)
      (^double getSpreadRate [this] spreadRate)
      (^double getTerrainDistance [this] terrainDistance)
      (^double getFractionalDistance [this] fractionalDistance)
      (^double getBurnProbability [this] burnProbability)
      (^void setI [this ^long newI] (set! i newI))
      (^void setJ [this ^long newJ] (set! j newJ))
      (^void setDirection [this ^byte newDirection] (set! direction newDirection))
      (^void setSpreadRate [this ^double newSpreadRate] (set! spreadRate newSpreadRate))
      (^void setTerrainDistance [this ^double newTerrainDistance] (set! terrainDistance newTerrainDistance))
      (^void setFractionalDistance [this ^double newFractionalDistance] (set! fractionalDistance newFractionalDistance))
      (^void setBurnProbability [this ^double newBurnProbability] (set! burnProbability newBurnProbability)))

  (defn make-burn-trajectory-vmtype
    [i j direction spread-rate terrain-distance fractional-distance burn-probability]
    (->VolatileMutableBurnTrajectory i j direction spread-rate terrain-distance fractional-distance burn-probability))

  (deftype UnsynchronizedMutableBurnTrajectory
      [^:unsynchronized-mutable ^long  i
       ^:unsynchronized-mutable ^long  j
       ^:unsynchronized-mutable ^byte  direction
       ^:unsynchronized-mutable ^double spreadRate
       ^:unsynchronized-mutable ^double terrainDistance
       ^:unsynchronized-mutable ^double fractionalDistance
       ^:unsynchronized-mutable ^double burnProbability]
      IBurnTrajectory
      (^long getI [this] i)
      (^long getJ [this] j)
      (^byte getDirection [this] direction)
      (^double getSpreadRate [this] spreadRate)
      (^double getTerrainDistance [this] terrainDistance)
      (^double getFractionalDistance [this] fractionalDistance)
      (^double getBurnProbability [this] burnProbability)
      (^void setI [this ^long newI] (set! i newI))
      (^void setJ [this ^long newJ] (set! j newJ))
      (^void setDirection [this ^byte newDirection] (set! direction newDirection))
      (^void setSpreadRate [this ^double newSpreadRate] (set! spreadRate newSpreadRate))
      (^void setTerrainDistance [this ^double newTerrainDistance] (set! terrainDistance newTerrainDistance))
      (^void setFractionalDistance [this ^double newFractionalDistance] (set! fractionalDistance newFractionalDistance))
      (^void setBurnProbability [this ^double newBurnProbability] (set! burnProbability newBurnProbability)))

  (defn make-burn-trajectory-umtype
    [i j direction spread-rate terrain-distance fractional-distance burn-probability]
    (->UnsynchronizedMutableBurnTrajectory i j direction spread-rate terrain-distance
                                           fractional-distance burn-probability))

  (def i                    4)
  (def j                    4)
  (def direction            0)
  (def spread-rate          10.0)
  (def terrain-distance     100.0)
  (def fractional-distance  0.5)
  (def burn-probability     1.0)

  (require '[criterium.core :refer [quick-bench]])

  (quick-bench
   (make-burn-trajectory-map i j direction spread-rate terrain-distance fractional-distance burn-probability))

  ;;            Execution time mean : 17.18 ns 18.10 ns

  (quick-bench
   (make-burn-trajectory-record i j direction spread-rate terrain-distance fractional-distance burn-probability))

  ;;            Execution time mean : 19.16 ns 18.10 ns

  (quick-bench
   (make-burn-trajectory-itype i j direction spread-rate terrain-distance fractional-distance burn-probability))

  ;;            Execution time mean : 18.14 ns 18.41ns

  (quick-bench
   (make-burn-trajectory-vmtype i j direction spread-rate terrain-distance fractional-distance burn-probability))

  ;;            Execution time mean : 22.77 ns 23.05 ns

  (quick-bench
   (make-burn-trajectory-umtype i j direction spread-rate terrain-distance fractional-distance burn-probability))

  ;;            Execution time mean : 18.38 ns 18.44 ns

  (quick-bench
   (vec (repeatedly 10000
                    #(make-burn-trajectory-map i j direction spread-rate
                                               terrain-distance fractional-distance burn-probability))))

  ;;            Execution time mean : 1.86 ms 2.16 ms

  (quick-bench
   (vec (repeatedly 10000
                    #(make-burn-trajectory-record i j direction spread-rate
                                                  terrain-distance fractional-distance burn-probability))))

  ;;            Execution time mean : 2.14 ms 2.14 ms

  (quick-bench
   (vec (repeatedly 10000
                    #(make-burn-trajectory-itype i j direction spread-rate
                                                 terrain-distance fractional-distance burn-probability))))

  ;;            Execution time mean : 2.12 ms 2.10 ms

  (quick-bench
   (vec (repeatedly 10000
                    #(make-burn-trajectory-vmtype i j direction spread-rate
                                                  terrain-distance fractional-distance burn-probability))))

  ;;            Execution time mean : 2.22 ms 2.21 ms

  (quick-bench
   (vec (repeatedly 10000
                    #(make-burn-trajectory-umtype i j direction spread-rate
                                                  terrain-distance fractional-distance burn-probability))))

  ;;            Execution time mean : 2.09 ms 2.14 ms

  (def burn-trajectories-map
    (vec (repeatedly 10000
                     #(make-burn-trajectory-map i j direction spread-rate
                                                terrain-distance fractional-distance burn-probability))))

  (def burn-trajectories-record
    (vec (repeatedly 10000
                     #(make-burn-trajectory-record i j direction spread-rate
                                                   terrain-distance fractional-distance burn-probability))))

  (def burn-trajectories-itype
    (vec (repeatedly 10000
                     #(make-burn-trajectory-itype i j direction spread-rate
                                                  terrain-distance fractional-distance burn-probability))))

  (def burn-trajectories-vmtype
    (vec (repeatedly 10000
                     #(make-burn-trajectory-vmtype i j direction spread-rate
                                                   terrain-distance fractional-distance burn-probability))))

  (def burn-trajectories-umtype
    (vec (repeatedly 10000
                     #(make-burn-trajectory-umtype i j direction spread-rate
                                                   terrain-distance fractional-distance burn-probability))))

  (let [bt-map (make-burn-trajectory-map i j direction spread-rate terrain-distance
                                         fractional-distance burn-probability)]
    (quick-bench (:fractional-distance bt-map)))

  ;;            Execution time mean : 17.09 ns 17.48 ns

  (let [bt-map (make-burn-trajectory-map i j direction spread-rate terrain-distance
                                         fractional-distance burn-probability)]
    (quick-bench (bt-map :fractional-distance)))

  ;;            Execution time mean : 10.90 ns 9.91 ns

  (let [bt-map (make-burn-trajectory-map i j direction spread-rate terrain-distance
                                         fractional-distance burn-probability)]
    (quick-bench (get bt-map :fractional-distance)))

  ;;            Execution time mean : 11.22 ns 16.64 ns

  (let [bt-record (make-burn-trajectory-record i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (:fractional-distance bt-record)))

  ;;            Execution time mean : 8.21 ns 8.35 ns

  (let [bt-record (make-burn-trajectory-record i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (get bt-record :fractional-distance)))

  ;;            Execution time mean : 19.20 ns 17.51 ns

  (let [bt-record (make-burn-trajectory-record i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (.fractional_distance bt-record)))

  ;;            Execution time mean : 2.29 us 2.37 us

  (let [bt-record (make-burn-trajectory-record i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (.-fractional_distance bt-record)))

  ;;            Execution time mean : 1.25 us 1.31 us

  (let [bt-itype (make-burn-trajectory-itype i j direction spread-rate terrain-distance
                                             fractional-distance burn-probability)]
    (quick-bench (.fractionalDistance bt-itype)))

  ;;            Execution time mean : 969.91 ns 1.00 us

  (let [bt-itype (make-burn-trajectory-itype i j direction spread-rate terrain-distance
                                             fractional-distance burn-probability)]
    (quick-bench (.-fractionalDistance bt-itype)))

  ;;            Execution time mean : 344.74 ns 364.89 ns

  (let [bt-itype (make-burn-trajectory-itype i j direction spread-rate terrain-distance
                                             fractional-distance burn-probability)]
    (quick-bench (.getFractionalDistance bt-itype)))

  ;;            Execution time mean : 1.24 us 1.24 us

  (let [bt-vmtype (make-burn-trajectory-vmtype i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (.getFractionalDistance bt-vmtype)))

  ;;            Execution time mean : 1.24 us 1.22 us

  (let [bt-umtype (make-burn-trajectory-umtype i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (.getFractionalDistance bt-umtype)))

  ;;            Execution time mean : 1.33 us 1.24 us

  ;;===================================================================================

  (let [bt-map (make-burn-trajectory-map i j direction spread-rate terrain-distance
                                         fractional-distance burn-probability)]
    (quick-bench (assoc bt-map :fractional-distance (+ 0.5 (bt-map :fractional-distance)))))

  ;;            Execution time mean : 44.54 ns 44.76 ns

  (let [bt-record (make-burn-trajectory-record i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (assoc bt-record :fractional-distance (+ 0.5 (:fractional-distance bt-record)))))

  ;;            Execution time mean : 26.07 ns 26.22 ns

  (let [bt-vmtype (make-burn-trajectory-vmtype i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (.setFractionalDistance bt-vmtype (+ 0.5 (.getFractionalDistance bt-vmtype)))))

  ;;            Execution time mean : 2.97 us 2.98 us

  (let [bt-umtype (make-burn-trajectory-umtype i j direction spread-rate terrain-distance
                                               fractional-distance burn-probability)]
    (quick-bench (.setFractionalDistance bt-umtype (+ 0.5 (.getFractionalDistance bt-umtype)))))

  ;;            Execution time mean : 3.19 us 3.00 us

  )
