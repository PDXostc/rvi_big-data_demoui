; Copyright (C) 2015, Jaguar Land Rover
; This Source Code Form is subject to the terms of the Mozilla Public
; License, v. 2.0. If a copy of the MPL was not distributed with this
; file, You can obtain one at http://mozilla.org/MPL/2.0/. 

(ns rvi-demo.prod
  (:require [rvi-demo.core :as core]))

(set! *print-newline* false)
(set! *print-fn*
      (fn [& args] ))

(core/main)
