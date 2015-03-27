; Copyright 2015, ATS Advanced Telematic Systems GmbH
; All Rights Reserved

(ns rvi-demo.prod
  (:require [rvi-demo.core :as core]))

(set! *print-newline* false)
(set! *print-fn*
      (fn [& args] ))

(core/main)
