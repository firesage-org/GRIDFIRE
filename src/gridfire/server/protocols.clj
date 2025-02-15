;; [[file:../../../org/GridFire.org::gridfire.server.protocols)][gridfire.server.protocols)]]
(ns gridfire.server.protocols)

(defprotocol JobHandler
  (schedule-command [this command =notifications-channel=] "Schedules the given command to be processed.
  Returns a Manifold Deferred of the processing result.
  =notifications-channel= must be a core.async channel, which will receive progress notifications.")
  (n-queued [this] "Returns the number of commands currently waiting to be processed.")
  (halt [this] "Terminates the logical process handling commands."))
;; gridfire.server.protocols) ends here
