# Rules for the Kotlin app; each rule says why.
#
# No rules yet: the libraries' own consumer rules (kotlinx.serialization, Room, WorkManager,
# startup) keep every class reached by name, and R8 reports no missing classes.
# Add a rule only when a class reached by name is removed or renamed, with a "why" line above it.
# Never keep a whole package (-keep class com.sadad.** { *; }); that hides the real cause.
