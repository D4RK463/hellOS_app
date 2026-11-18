# hellOS parses the protocol with Jackson's tree model (no data binding by reflection),
# but Jackson creates some of its internals reflectively. Keep both libraries intact.
-keep class org.hellos.** { *; }
-keep class tools.jackson.** { *; }
-dontwarn tools.jackson.**
