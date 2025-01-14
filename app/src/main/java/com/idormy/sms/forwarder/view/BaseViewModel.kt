package com.idormy.sms.forwarder.view

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

abstract class BaseViewModel : ViewModel() {
    var progressLiveEvent = MutableLiveData<Boolean>()
    var errorMessage = MutableLiveData<String>()

    inline fun <T> launchAsync(
        crossinline execute: suspend () -> T,
        crossinline onSuccess: (T) -> Unit,
        delayTimeMillis: Long = 0,
        showProgress: Boolean = true,
    ) {
        if (showProgress) {
            progressLiveEvent.postValue(true)
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (delayTimeMillis > 0) {
                delay(delayTimeMillis)
            }
            try {
                onSuccess(execute())
            } catch (ex: Exception) {
                errorMessage.postValue(ex.message)
            } finally {
                progressLiveEvent.postValue(false)
            }
        }
    }

    inline fun <T> launchPagingAsync(
        crossinline execute: suspend () -> Flow<T>,
        crossinline onSuccess: (Flow<T>) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = execute()
                onSuccess(result)
            } catch (ex: Exception) {
                errorMessage.value = ex.message
            }
        }
    }
}