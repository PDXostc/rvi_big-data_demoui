(ns rvi-demo.pickups
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [om.core :as om]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [ajax.core :refer (GET)]
            [om-bootstrap.grid :as g]
            [cljsjs.d3]
            [cljs.core.async :refer [<! alts! chan put! sliding-buffer]]))

(defn api-uri
  []
  (.-api_uri js/conf))

(defn get-positions
  [[dateFrom dateTo] [hourFrom hourTo] cursor]
  (GET (str (api-uri) "pickups")
       {:params {:dateFrom dateFrom
                 :dateTo dateTo
                 :hourFrom hourFrom
                 :hourTo hourTo}
        :handler (fn [points] (om/update! cursor [:positions] points))}))

(defn draw-pos
  [map data]
  (-> js/L (.heatLayer (clj->js data) #js {:radius 10 :max 1}) (.addTo map)))

(defn draw-map
  [cursor]
  (-> js/L (.map "map")
      (.setView #js [37.76084 -122.39522] 13)))


(defcomponent positions-comp
  [cursor owner]

  (init-state
    [_]
    {:leaflet-map nil :tile-layer nil})

  (render-state
    [_ _]
    (dom/div #js {:id "map"} nil))

  (did-mount
    [_]
    (let [m (draw-map cursor)
          lurl (get-in cursor [:osm :url])
          lattr (get-in cursor [:osm :attrib])
          t (L.TileLayer. lurl #js {:minZoom 1 :maxZoom 19, :attribution lattr :id "examples.map-20v6611k"})]
      (.addLayer m t)
      (draw-pos m (:positions cursor))
      (om/update-state! owner #(merge % {:leaflet-map m :tile-layer t}))))

  (did-update
    [_ _ {:keys [leaflet-map tile-layer]}]
    (if leaflet-map
      (do
        (.eachLayer leaflet-map (fn [layer] (if (not= layer tile-layer)
                                              (.removeLayer leaflet-map layer))))
        (draw-pos leaflet-map (:positions cursor))))))

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

(defcomponent date-from
  [cursor owner]
  (render
    [_]
    (dom/div {:id "date-from" :class "input-group date" :style {:margin-top "13px"}}
             (dom/input {:type "text" :class "form-control"}
                        (dom/span {:class "input-group-addon"}
                                  (dom/i {:class "glyphicon glyphicon-th"})))))

  (did-mount
    [_]
    (doto (js/$ "#date-from")
      (.datepicker  (clj->js {:format "dd/mm/yyyy"}))
      (.datepicker "update" (get cursor 0))
      (-> (.datepicker) (.on "changeDate" (fn [e]
                                            (put! (om/get-state owner [:time-chan]) [:from-date (.-date e)])
                                            (om/transact! cursor [0] #(.-date e))))))))

(defcomponent date-to
  [cursor owner]
  (render-state
    [_ _]
    (dom/div {:id "date-to" :class "input-group date" :style {:margin-top "13px"}}
             (dom/input {:type "text" :class "form-control"}
                        (dom/span {:class "input-group-addon"}
                                  (dom/i {:class "glyphicon glyphicon-th"})))))

  (did-mount
    [_]
    (doto (js/$ "#date-to")
      (.datepicker  (clj->js {:format "dd/mm/yyyy"}))
      (.datepicker "update" (get cursor 0))
      (-> (.datepicker) (.on "changeDate" (fn [e]
                                            (do
                                              (put! (om/get-state owner [:time-chan]) [:to-date (.-date e)])
                                              (om/transact! cursor [0] #(.-date e)))))))))

(defcomponent pickups-dropoffs
  [cursor owner]

  (init-state
    [_]
    {:chans {:time-chan (chan (sliding-buffer 1))}})

  (render-state [_ {:keys [chans]}]
    (prn (:date-range cursor))
    (dom/div
      (g/grid {}
              (g/row {}
                     (om/build positions-comp cursor))
              (g/row {}
                     (g/col {:md 2} (om/build date-from (get-in cursor [:date-range :from]) {:init-state chans}))
                     (g/col {:md 2} (om/build date-to (get-in cursor [:date-range :to]) {:init-state chans}))
                     (g/col {:md 8} (om/build hours (:hours cursor) {:init-state chans}))))))
  (will-mount
    [_]
    #_(get-positions [] cursor)
    (go-loop []
             (let [ctime (om/get-state owner [:chans :time-chan])
                   event (<! ctime)]
               (case (event 0)
                 :hours (get-positions (map #(.getTime (get % 0)) [(get-in @cursor [:date-range :from]) (get-in @cursor [:date-range :to])]) (event 1) cursor)
                 :to-date (get-positions (map #(.getTime %) [(get-in @cursor [:date-range :from 0]) (event 1)]) (:hours @cursor) cursor)
                 :from-date (get-positions (map #(.getTime %) [(event 1) (get-in @cursor [:date-range :to 0])]) (:hours @cursor) cursor)))
               (recur))))