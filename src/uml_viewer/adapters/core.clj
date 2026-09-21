(ns uml-viewer.adapters.core
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [uml-viewer.adapters.sketch :as sketch]))

(def help-text
  (str "Usage: clj -M:run [options] [edn-file]\n"
       "\n"
       "  edn-file          Diagram to watch (default: examples/library.edn).\n"
       "                    A fresh start waits for the companion Grok to send\n"
       "                    :display unless the associated agent recycles\n"
       "                    the window with :uml-viewer-restart, or you press R.\n"
       "\n"
       "  --restart         Associated agent only (via :uml-viewer-restart).\n"
       "                    New JVM, keep the existing Grok tmux session.\n"
       "                    Reloads the last view (depth, pan, zoom, proposal).\n"
       "                    Do not use this if no companion is attached.\n"
       "\n"
       "  --companion-command=VECTOR\n"
       "                    Start this agent instead of Grok, e.g.\n"
       "                    '[\"claude\" \"--append-system-prompt\" \"{rules}\" \"{prompt}\"]'.\n"
       "                    {rules} and {prompt} become the standing rules and\n"
       "                    the launch prompt. The mailbox is unchanged.\n"
       "\n"
       "  --no-companion    Start no agent. Something else serves the mailbox\n"
       "                    in .uml-viewer/, or you press R to load the file.\n"
       "\n"
       "  -h, --help        Print this help and exit.\n"))

(def ^:private command-flag "--companion-command=")

(defn companion-command
  "The vector of strings after --companion-command=, or nil when absent.
  Throws on anything else. tmux hands a lone string to a shell, so a
  one-element command must be a plain program path, and tmux would read a
  leading dash as one of its own options."
  [args]
  (when (some #{"--companion-command"} args)
    (throw (ex-info "Write --companion-command=VECTOR, with the equals sign" {})))
  (when-let [text (some #(when (str/starts-with? % command-flag)
                           (subs % (count command-flag)))
                        args)]
    (let [command (try (edn/read-string text) (catch Exception _ nil))]
      (if (and (vector? command) (seq command)
               (every? #(and (string? %) (seq %)) command)
               (not (str/starts-with? (first command) "-"))
               (or (next command) (re-matches #"[A-Za-z0-9_./+-]+" (first command))))
        command
        (throw (ex-info (str "--companion-command needs a vector of strings: "
                             "a program, then its arguments, with {rules} "
                             "and {prompt} as separate elements")
                        {:value text}))))))

(defn parse-args
  "EDN path and flags. `--restart` skips spawning a new agent."
  [args]
  (let [args (keep identity args)
        help? (boolean (some #{"--help" "-h"} args))
        restart? (boolean (some #{"--restart"} args))
        no-companion? (boolean (some #{"--no-companion"} args))
        command (when-not help? (companion-command args))
        path (->> args
                  (remove #{"--help" "-h" "--restart" "--no-companion"})
                  (remove #(str/starts-with? % command-flag))
                  first)]
    (when (and command no-companion?)
      (throw (ex-info "--no-companion and --companion-command contradict each other" {})))
    (cond-> {:help? help?
             :restart? restart?
             :path (or path "examples/library.edn")}
      no-companion? (assoc :no-companion? true)
      command (assoc :companion-command command))))

(defn start!
  "Launch the viewer. `source-impl` satisfies `LanguageSource`."
  [source-impl & args]
  (let [{:keys [path restart? help?] :as parsed} (parse-args args)
        companion (select-keys parsed [:companion-command :no-companion?])]
    (if help?
      (do (print help-text) :help)
      (do
        (if (seq companion)
          (sketch/start! path source-impl restart? companion)
          (sketch/start! path source-impl restart?))
        (println "Watching" path)
        (println "Double-click a class for its card. Scroll to pan (Shift-scroll for horizontal). Ctrl+/− zoom; Ctrl+0 resets. R reloads. Click the real diagram above Proposals, or a proposal to show it.")))))
