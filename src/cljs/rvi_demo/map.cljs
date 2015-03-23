(ns rvi-demo.map
  (:require [om.core :as om]
            [om-tools.dom :as dom :include-macros true]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.mixin :refer-macros [defmixin]]
            [cljsjs.d3]
            [cljs.core.async :refer [<! alts! chan put! sliding-buffer]]))

(defn polygon->coord-vec
  [polygon]
  (map
    (fn [lat-lng] [(.-lat lat-lng) (.-lng lat-lng)])
    (.getLatLngs polygon)))

(defn- put-area!
  [owner polygon]
  (put! (om/get-state owner [:area]) polygon))

(defmixin leaflet-map

  (will-mount
    [owner]
    (let [url    "https://{s}.tiles.mapbox.com/v3/{id}/{z}/{x}/{y}.png"
          attrib "Map data © OpenStreetMap contributors"]
      (set! (. owner -tile-layer) (L.TileLayer. url #js {:minZoom 1 :maxZoom 19, :attribution attrib :id "examples.map-20v6611k"}))
      (set! (. owner -filter-layer) (js/L.FeatureGroup.))
      (set! (. owner -view) #js [37.76084 -122.39522])))

  (mk-map
    [owner id layers]
    (let [t (. owner -tile-layer)
          f (. owner -filter-layer)
          opts (clj->js {:center (. owner -view)
                         :zoom   11
                         :layers (into [t f] layers)})
          m (-> js/L (.map id opts))]
      (set! (. owner -leaflet) m)
      (.addControl m (js/L.Control.Draw. (clj->js {:edit {:featureGroup f}
                                                   :draw {:polyline false
                                                          :circle   false
                                                          :marker   false}})))
      (.on m "draw:created" (fn [e]
                              (let [layer (.-layer e)]
                                (.clearLayers f)
                                (.addLayer f layer)
                                (put-area! owner layer))))
      (.on m "draw:edited" (fn [e]
                             (-> e (.-layers) (.eachLayer (fn [l] (put-area! owner l))))))
      (.on m "draw:deleted" (fn [_]
                              (put! (om/get-state owner [:area]) (.polygon js/L []))))
      m))

  (reset-layers!
    [owner]
    (let [leaflet-map (. owner -leaflet)
          tile-layer (. owner -tile-layer)
          filter-layer (. owner -filter-layer)]
      (.eachLayer leaflet-map (fn [layer] (if (and
                                                (not (contains? #{tile-layer filter-layer} layer))
                                                (not (.hasLayer filter-layer layer)))
                                            (.removeLayer leaflet-map layer)))))))
