# Rules for the Kotlin app; each rule says why.
#
# No rules yet. Ticket 64 checked the R8 output (usage.txt, seeds.txt, missing_rules.txt) of
# assembleRelease: the libraries' own consumer rules (kotlinx.serialization, Room, WorkManager,
# startup) keep every class reached by name (@Serializable classes, route classes, LedgerDatabase_Impl,
# SyncWorker, DueReminderWorker), and R8 reported no missing classes.
# Add a rule only when a class reached by name is removed or renamed, with a "why" line above it.
# Never keep a whole package (-keep class com.sadad.** { *; }); that hides the real cause.
