(ns rvi-demo.live-data
  (:require-macros [cljs.core.async.macros :refer [go]])
  (:require [om.core :as om]
            [cljs-wsock.core :as ws]
            [cljs.reader :as reader]
            [cljs.core.async :refer [<!]]
            [om-tools.dom :as dom :include-macros true]
            [om-bootstrap.grid :as g]
            [om-bootstrap.panel :as p]
            [om-tools.core :refer-macros [defcomponent defcomponentk]]
            [secretary.core :as sec :include-macros true]
            [rvi-demo.nav :as n]))


(def map-model
  (atom
    {:map {:leaflet-map nil
           :ws nil
           :map {:lat 37.75122, :lng -122.39522}}
     :markers {}
     :traces {}
     :osm {:url "http://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
           :attrib "Map data © OpenStreetMap contributors"}
     :socket nil}))

(defn mk-map
  [cursor]
  (let [lurl (get-in cursor [:osm :url])
        lattr (get-in cursor [:osm :attrib])]
    (-> js/L (.map "map")
        (.setView #js [37.76084 -122.39522] 11)
        (.addLayer (L.TileLayer. lurl #js {:minZoom 1 :maxZoom 19, :attribution lattr})))))

(defn get-leaflet [] (get-in @map-model [:map :leaflet-map]))

(defn marker-icon
  [occupied?]
  (js/L.icon. (clj->js {:iconUrl (if occupied? "occupied.png" "free.png")
                        :iconSize    [25, 41]
                        :iconAnchor  [12, 41]
                        :popupAnchor [1, -34]
                        :shadowSize  [41, 41]})))

(defn mk-marker
  [map data]
  (do
    (.log js/console "Create marker" data)
    (-> js/L
        (.marker (clj->js data) (clj->js {:icon (marker-icon (:occupied data))}))
        (.bindPopup (str "<p>" (:id data) "</p>"))
        (.addTo map))))

(defn update-marker
  [cursor event]
  (let [trace-id (get event :id)
        marker (get-in @cursor [:markers trace-id])
        pos (clj->js event)
        map (get-in @cursor [:map :leaflet-map])]
    (if marker
      (-> marker (.setLatLng pos) (.setIcon (marker-icon (:occupied event))) (.update))
      (om/update! cursor [:markers trace-id] (mk-marker map event)))))


(defn ws-connect
  [cursor]
  (go
    (let [ch (get-in @cursor [:map :ws])]
      (loop []
        (let [[status event] (<! ch)]
          (case status
            :opened (do
                      (.log js/console "Connection opened")
                      (recur))
            :message (let [data (reader/read-string (str event))]
                       (om/update! cursor [:traces (:id data)] data)
                       (update-marker cursor data)
                       (recur))
            :closed (.log js/console  "Connection closed")
            :error (.log js/console  "Error!!!")))))))

(defn ws-uri
  []
  (.-traces_uri js/conf))

(defn map-component
  [cursor _]
  (reify
    om/IWillMount
    (will-mount
      [_]
      (ws-connect cursor))

    om/IWillUnmount
    (will-unmount
      [_]
      (om/update! cursor [:map :leaflet-map] nil)
      (ws/close! (get-in cursor [:map :ws]))
      (om/update! cursor [:map :ws] nil))

    om/IRender
    (render [_]
      (dom/div #js {:id "map"} nil))

    om/IDidMount
    (did-mount [_]
      (let [leaflet (mk-map cursor)
            ch (ws/open! (ws-uri))]
        (om/update! cursor [:map :leaflet-map] leaflet)
        (om/update! cursor [:map :ws] ch)))))

(defn stats-component
  [cursor _]
  (om/component
    (let [total (count cursor)
          occupied (count (filter #(get-in % [1 :occupied]) cursor))
          free (- total occupied)]
      (dom/div
        (dom/p (dom/strong "Number of taxis") " : " total)
        (dom/p (dom/strong {:class "text-success"} "Free") " : " free)
        (dom/p (dom/strong {:class "text-danger"} "Occupied") " : " occupied)))))

(defn grid
  [cursor _]
  (om/component
    (dom/div
      (g/grid {}
              (g/row {}
                     (g/col {:md 6 :md-push 6}
                            (p/panel {:header "Summary"} (om/build stats-component (:traces cursor))))
                     (g/col {:md 6 :md-pull 6}
                            (p/panel {:header "Map"} (om/build map-component cursor))))
              )))
  )
