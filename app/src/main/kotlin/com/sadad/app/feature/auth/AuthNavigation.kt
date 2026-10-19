package com.sadad.app.feature.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import com.sadad.app.AppGraph
import com.sadad.app.SadadApplication

/** One factory for the four auth screens; the graph is the app-wide one. */
@Composable
internal inline fun <reified VM : ViewModel> authViewModel(
    noinline create: (AppGraph, SavedStateHandle, Context) -> VM
): VM =
    viewModel(
        factory =
            viewModelFactory {
              initializer {
                val app = this[APPLICATION_KEY] as SadadApplication
                create(app.graph, createSavedStateHandle(), app)
              }
            }
    )

/** Auth steps are one-way: the finished step leaves no entry to return to. */
fun NavHostController.navigateAuth(destination: Any) {
  navigate(destination) {
    popUpTo(graph.id) { inclusive = true }
    launchSingleTop = true
  }
}
