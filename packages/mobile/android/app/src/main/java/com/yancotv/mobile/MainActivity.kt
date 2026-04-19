package com.yancotv.mobile

import android.os.Bundle
import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

class MainActivity : ReactActivity() {

  override fun getMainComponentName(): String = "YancoTV"

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)

  // react-native-screens requires super.onCreate(null) on Android instead of
  // passing through savedInstanceState. Without this, fragment state restore
  // can re-attach stale screen fragments to a fresh navigator and blow up on
  // the first back-press after a process restore.
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(null)
  }
}
