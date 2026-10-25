package com.sadad.app.feature.contacts

import java.util.Locale

/** Arabic-Indic digits and the two Arabic decimal/grouping marks read as their Latin forms. */
internal fun searchText(value: String): String =
    buildString(value.length) {
          for (character in value.trim()) {
            when {
              character in '\u0660'..'\u0669' -> append('0' + (character - '\u0660'))
              character in '\u06F0'..'\u06F9' -> append('0' + (character - '\u06F0'))
              else -> append(character)
            }
          }
        }
        .lowercase(Locale.ROOT)
