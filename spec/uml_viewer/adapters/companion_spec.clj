(ns uml-viewer.adapters.companion-spec
  (:require [quil.core :as q]
            [speclj.core :refer :all]
            [uml-viewer.adapters.core :as core]
            [uml-viewer.adapters.sketch :as sketch]))

(def claude ["claude" "--append-system-prompt" "{rules}" "{prompt}"])

(describe "companion command line"
  (it "starts Grok exactly as before when no command is given"
    (let [expected ["new-session" "-d" "-s" "s" "-c" "/tmp/proj"
                    "-e" "GROK_THEME=terminal"
                    "-e" "GROK_TERMINAL_THEME=1"
                    "-e" "COLORTERM=truecolor"
                    (sketch/grok-executable) "--yolo" "--trust"
                    "--rules" sketch/standing-rules
                    sketch/launch-prompt]]
      (should= expected (sketch/new-session-args "/tmp/proj" "s"))
      (should= expected (sketch/new-session-args "/tmp/proj" "s" nil))))

  (it "starts another agent with the same rules and prompt"
    (let [args (sketch/new-session-args "/tmp/proj" "s" claude)]
      (should= ["claude" "--append-system-prompt" sketch/standing-rules sketch/launch-prompt]
               (vec (take-last 4 args)))
      (should-not (some #{"--yolo" "--trust"} args))
      (should-not (some #{(sketch/grok-executable)} args)))))

(describe "companion flags"
  (it "reads a vector of strings after --companion-command="
    (should= {:path "doc.edn" :restart? false :help? false :companion-command claude}
             (core/parse-args [(str "--companion-command=" (pr-str claude)) "doc.edn"]))
    (should= ["agent"] (core/companion-command ["--companion-command=[\"agent\"]"]))
    (should-be-nil (core/companion-command ["doc.edn"])))

  (it "refuses what tmux would hand to a shell or read as its own option"
    (doseq [bad ["claude {prompt}" "[]" "[\"\"]" "[\"agent\" 7]" "[\"agent {prompt}\"]" "{"
                 "[\"/My Agents/claude\"]" "[\"agent; rm -rf ~\"]" "[\"-A\"]" "[\"-t\" \"other\" \"agent\"]"]]
      (should-throw clojure.lang.ExceptionInfo
                    (core/companion-command [(str "--companion-command=" bad)])))
    (should= ["/opt/bin/agent-1.2"] (core/companion-command ["--companion-command=[\"/opt/bin/agent-1.2\"]"])))

  (it "refuses the flag without its equals sign, or together with --no-companion"
    (should-throw clojure.lang.ExceptionInfo (core/parse-args ["--companion-command" "[\"agent\"]" "doc.edn"]))
    (should-throw clojure.lang.ExceptionInfo
                  (core/parse-args ["--no-companion" "--companion-command=[\"agent\"]"])))

  (it "still prints help when the command is malformed"
    (should (:help? (core/parse-args ["--companion-command=oops" "-h"]))))

  (it "reads --no-companion and keeps it out of the path"
    (should= {:path "doc.edn" :restart? false :help? false :no-companion? true}
             (core/parse-args ["--no-companion" "doc.edn"])))

  (it "describes both flags in the help"
    (should (re-find #"--companion-command=" core/help-text))
    (should (re-find #"--no-companion" core/help-text))))

(describe "companion launch"
  (before (reset! sketch/!bridge {}))
  (after (reset! sketch/!bridge {}))

  (it "hands the command to the tmux launch"
    (let [launched (atom nil)]
      (with-redefs [sketch/start! (fn [& a] (reset! launched (vec a)))]
        (with-out-str (core/start! :src (str "--companion-command=" (pr-str claude)) "doc.edn")))
      (should= ["doc.edn" :src false {:companion-command claude}] @launched))
    (let [opened (atom nil)]
      (with-redefs [sketch/open-in-terminal! (fn [& a] (reset! opened (vec a)))
                    q/sketch (fn [& _] :applet)]
        (sketch/start! "examples/library.edn" :src false {:companion-command claude}))
      (should= claude (second @opened))))

  (it "starts, wakes and kills nothing with --no-companion, even beside a live session"
    (let [touched (atom [])]
      (with-redefs [sketch/open-in-terminal! (fn [& _] (swap! touched conj :opened))
                    sketch/shutdown-children! (fn [& _] (swap! touched conj :killed))
                    sketch/tmux! (fn [& args] (swap! touched conj (vec args)) 0)
                    uml-viewer.adapters.sketch/remember-companion! (fn [& _] (swap! touched conj :remembered))
                    uml-viewer.adapters.sketch/close-detail-window! (fn [])
                    uml-viewer.adapters.sketch/halt-vm! (fn [] (swap! touched conj :halted))
                    q/sketch (fn [& _] :applet)]
        (sketch/start! "examples/library.edn" :src false {:no-companion? true})
        (should= false (sketch/notify-agent! "."))
        (#'sketch/on-main-close {}))
      (should= [:halted] @touched))))
