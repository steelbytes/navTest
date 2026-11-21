Demonstration of Navigation API not always returning a result for setDestination.
(vibe coded with Claude)

* the error is NETWORK_ERROR after 60secs.
* The delay of 35sec between stopGuidance and destroy seems to be the determining factor.  Use 10secs and then all is fine.
* once setDestination fails then no amount of stopping, destroying etc will allow a new attempt.  Need to force stop the app.

My app does a destroy when idle because it is a long running app including having a NotificationListenerService and battery optimisation disabled.

Build note:
add MAPS_API_KEY=your_key to local.properties

