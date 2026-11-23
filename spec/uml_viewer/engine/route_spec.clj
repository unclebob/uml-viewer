(ns uml-viewer.engine.route-spec
  (:require [speclj.core :refer :all]
            [uml-viewer.domain.geom :as geom]
            [uml-viewer.engine.route]))

(def ^:private classes
  [{:rect (geom/rect 10 20 30 40)}
   {:rank 0 :rect (geom/rect 50 5 10 15)}
   {:rank 1 :rect (geom/rect 100 80 20 25)}])

(describe "rank channels"
  (it "measures each side of a rank, or nothing when the rank is empty"
    (let [extent (ns-resolve 'uml-viewer.engine.route 'rank-extent)]
      (should= 60 (extent classes 0 true :after))
      (should= 10 (extent classes 0 true :before))
      (should= 60 (extent classes 0 false :after))
      (should= 5 (extent classes 0 false :before))
      (should-be-nil (extent classes 3 true :after))
      (should-be-nil (extent [] 0 false :before))))

  (it "opens a channel between two ranks in either direction"
    (let [channel (ns-resolve 'uml-viewer.engine.route 'channel)]
      (should= {:lo 60 :hi 100 :span 40} (channel classes 0 1 true))
      (should= {:lo 60 :hi 100 :span 40} (channel classes 1 0 true))
      (should= {:lo 60 :hi 80 :span 20} (channel classes 0 1 false))
      (should-be-nil (channel classes 0 4 true))
      (should-be-nil (channel classes 5 6 false))))

  (it "routes around a rank on a horizontal lane"
    (let [via (ns-resolve 'uml-viewer.engine.route 'via-rank)
          ranked [{:rank 1 :rect (geom/rect 100 80 20 25)}]]
      (should= [[80.0 45.0] [140.0 45.0]]
               (mapv #(mapv double %)
                     (via ranked 1 [0 10] [50 80] 1 true)))))

  (it "doglegs between stacked boxes that do not share a face span"
    (let [along (ns-resolve 'uml-viewer.engine.route 'along-stack)
          from {:rect (geom/rect 0 0 10 10)}
          to {:rect (geom/rect 40 30 10 10)}]
      (should= [[5.0 10.0] [5.0 20.0] [45.0 20.0] [45.0 30.0]]
               (mapv #(mapv double %) (along from to 0.5 0.5 true)))
      (let [from {:rect (geom/rect 0 0 10 10)}
            to {:rect (geom/rect 30 40 10 10)}]
        (should= [[10.0 5.0] [20.0 5.0] [20.0 45.0] [30.0 45.0]]
                 (mapv #(mapv double %) (along from to 0.5 0.5 false))))))

  (it "keeps the first reversing path when none attach cleanly"
    (let [try-paths (ns-resolve 'uml-viewer.engine.route 'try-paths)
          rev1 [[0 0] [100 0] [10 0]]
          rev2 [[0 0] [200 0] [10 0]]]
      (should= rev1 (try-paths [rev1 rev2] :a :b []))))

  (it "falls back to a blocked candidate when every path hits a class"
    (let [try-paths (ns-resolve 'uml-viewer.engine.route 'try-paths)
          hit [[0 5] [20 5]]
          wall {:id :w :rect (geom/rect 0 0 10 10)}]
      (should= hit (try-paths [hit] :a :b [wall]))))

  (it "accepts a stub when either end is missing"
    (let [stub-ok? (ns-resolve 'uml-viewer.engine.route 'stub-ok?)]
      (should (stub-ok? nil [0 0] [1 1]))))

  (it "does not call a short or stationary path a reversal"
    (let [reverses? (ns-resolve 'uml-viewer.engine.route 'reverses-past-target?)]
      (should-not (reverses? [[0 0] [1 1]]))
      (should-not (reverses? [[0 0] [10 0] [0 0]])))))

(def ^:private column
  "Three collapsed packages stacked in one column, as Declutter classes draws a proposal."
  [{:id :top :rank 0 :dummy? true :rect (geom/rect 0 0 100 50)}
   {:id :mid :rank 1 :dummy? true :rect (geom/rect 0 100 100 50)}
   {:id :bot :rank 2 :dummy? true :rect (geom/rect 0 200 100 50)}])

(describe "collapsed packages"
  (it "treats a lone dummy as a box to avoid, and a dummy around real classes as a frame"
    (let [hits? (ns-resolve 'uml-viewer.engine.route 'path-hits?)
          frame {:id :frame :dummy? true :rect (geom/rect 0 90 100 70)}
          inner {:id :inner :rect (geom/rect 20 110 60 30)}
          beside-inner [[10 50] [10 200]]]
      (should (hits? [[50 50] [50 200]] :top :bot column))
      (should-not (hits? beside-inner :top :bot [(first column) frame inner (last column)]))
      (should (hits? beside-inner :top :bot [(first column) frame (last column)]))))

  (it "routes an edge that skips a rank in a side lane clear of the box between"
    (let [lane (ns-resolve 'uml-viewer.engine.route 'side-lane)
          hits? (ns-resolve 'uml-viewer.engine.route 'path-hits?)
          [top _ bot] column
          down (lane top bot 0.5 0.5 0 column false)
          up (lane bot top 0.5 0.5 0 column false)]
      (should-not (hits? down :top :bot column))
      (should-not (hits? up :bot :top column))
      (should (< (apply min (map first down)) 0))
      (should (> (apply max (map first up)) 100))
      (should-be-nil (lane top (second column) 0.5 0.5 0 column false)))))
