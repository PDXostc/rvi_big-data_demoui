(ns rvi-demo.pickups
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [ajax.core :refer (GET)]
            [om-bootstrap.grid :as g]
            [cljsjs.d3]
            [rvi-demo.datepicker :as dp]
            [rvi-demo.map :as map]
            [cljs.core.async :refer [<! alts! chan put! sliding-buffer]]))

(defn api-uri
  []
  (.-api_uri js/conf))

(defn get-positions
  [[dateFrom dateTo] [hourFrom hourTo] area cursor]
  (GET (str (api-uri) "pickups")
       {:params  {:dateFrom dateFrom
                  :dateTo   dateTo
                  :hourFrom hourFrom
                  :hourTo   hourTo
                  :area     area}
        :handler (fn [points] (om/update! cursor [:positions] points))}))

(defn draw-pos
  [map data]
  (-> js/L (.heatLayer (clj->js data) #js {:radius 10 :max 1}) (.addTo map)))

(defcomponent map-comp
  [cursor owner {:keys [area]}]
  (:mixins map/leaflet-map)

  (init-state
    [_]
    {:area area})

  (did-mount
    [_]
    (-> (.mk-map owner "map" []) (draw-pos (:positions cursor))))

  (did-update
    [_ _ _]
    (do
      (.reset-layers! owner)
      (draw-pos (. owner -leaflet) (:positions cursor))
      (.bringToFront (. owner -filter-layer))))

  (render
    [_ ]
    (dom/div {:id "map"})))

(defn select [selector]
  (-> js/d3 (.select selector)))

(defn append
  [sel nm attributes]
  (reduce
    (fn [acc [n v]] (.attr acc (clj->js n) (clj->js v)))
    (-> sel (.append nm) )
    (seq attributes)))

(defn- draw-svg
  []
  (-> (select "#hours-slider")
      (append "svg" {:width 900 :height 100})
      (append "g" {:transform "translate(20, 20)"})))

(def domain [0 24])

(defn- hours-scale
  []
  (-> d3.scale
      (.linear)
      (.range #js [0 700])
      (.domain (clj->js domain))))

(defn- arc
  []
  (-> d3.svg
      (.arc)
      (.outerRadius 10)
      (.startAngle 0)
      (.endAngle (fn [_ i] (if (= i 1) (- js/Math.PI) js/Math.PI)))))

(defn round
  [d precision]
  (let [factor (Math/pow 10 precision)]
    (/ (Math/floor (* d factor)) factor)))

(defn- brushcenter
  [brush scale brushg]
  (fn []
    (let [extent (js->clj (.extent brush))
          size (- (extent 1) (extent 0))
          target (.-target js/d3.event )
          pos (round (.invert scale ((js->clj (.mouse js/d3 target)) 0)) 0)
          x0 (round (- pos (/ size 2)) 0)
          x1 (round (+ pos (/ size 2)) 0)
          new-extent (cond
                       (< x0 (domain 0)) [0 size]
                       (> x1 (domain 1)) [(- (domain 1) size) (domain 1)]
                       :else [x0 x1])]
      (.stopPropagation (.-event js/d3))
      (((.-extent brush) (clj->js new-extent)) brushg)
      ((.-event brush) brushg))))

(defn- draw-brush
  [target cursor x channel]
  (let [ brush (-> d3.svg (.brush) (.x x) (.extent (clj->js cursor)))]
    (brush target)
    (append (.selectAll target ".resize") "path" {:transform "translate(20, 9)" :d (arc)})
    (-> (.selectAll target "rect") (.attr "height" 18) (.attr "transform" "translate (20, 0)"))
    (.on brush "brushend"
         (fn []
           (if (.-sourceEvent js/d3.event)
             (this-as this
                      (let [ext (map #(round % 0 ) (js->clj (.extent brush)) )]
                        (-> this (select)
                            (.transition)
                            (.call ((.-extent brush) (clj->js ext)))
                            (.call (.-event brush)))
                        (om/update! cursor ext)
                        (put! channel [:hours ext]))))))
    (-> (.select target ".background") (.on "mousedown.brush" (brushcenter brush x target)))))

(defn- draw-axis
  [parent scale]
  ((-> d3.svg (.axis) (.scale scale) (.orient "bottom")) parent))

(defcomponent hours
  [cursor owner]
  (render-state
    [_ _]
    (dom/div #js {:id "hours-slider"}))

  (did-mount
    [_]
    (let [x (hours-scale)
          svg (draw-svg)]
      (draw-axis
        (append svg "g" {:class "x axis" :transform "translate(20, 20)"})
        x)
      (draw-brush (append svg "g" {:class "brush"}) cursor x (om/get-state owner [:time-chan])))))

(defcomponent pickups-dropoffs
  [cursor owner]

  (init-state
    [_]
    {:chans {:time-chan (chan (sliding-buffer 1))
             :area      (chan 1 (map (fn [polygon] [:area (map/polygon->coord-vec polygon)])))}})

  (render-state [_ {:keys [chans]}]
    (dom/div
      (g/grid {}
              (g/row {}
                     (->map-comp cursor {:opts chans}))
              (g/row {}
                     (g/col {:md 2} (om/build dp/datepicker (get-in cursor [:date-range :from]) {:opts {:id "date-from"
                                                                                                        :on-change (fn [e]
                                                                                                                     (put! (:time-chan chans) [:from-date (.-date e)]))}}))
                     (g/col {:md 2} (om/build dp/datepicker (get-in cursor [:date-range :to]) {:opts {:id "date-to"
                                                                                                      :on-change (fn [e]
                                                                                                                   (put! (:time-chan chans) [:to-date (.-date e)]))}}))
                     (g/col {:md 8} (om/build hours (:hours cursor) {:init-state chans}))))))
  (will-mount
    [_]
    (go-loop []
             (let [ctime (om/get-state owner [:chans :time-chan])
                   carea (om/get-state owner [:chans :area])
                   [event _] (alts! [ctime carea])]
               (case (event 0)
                 :hours (get-positions (map #(.getTime (get % 0)) [(get-in @cursor [:date-range :from]) (get-in @cursor [:date-range :to])])
                                       (event 1)
                                       (get-in @cursor [:area])
                                       cursor)
                 :to-date (get-positions (map #(.getTime %) [(get-in @cursor [:date-range :from 0]) (event 1)])
                                         (:hours @cursor)
                                         (get-in @cursor [:area])
                                         cursor)
                 :from-date (get-positions (map #(.getTime %) [(event 1) (get-in @cursor [:date-range :to 0])])
                                           (:hours @cursor)
                                           (get-in @cursor [:area])
                                           cursor)
                 :area (do
                         (om/update! cursor [:area] (event 1))
                         (get-positions (map #(.getTime (get % 0)) [(get-in @cursor [:date-range :from]) (get-in @cursor [:date-range :to])])
                                        (:hours @cursor)
                                        (event 1)
                                        cursor))))
             (recur))))