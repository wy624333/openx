package com.wy.openx.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wy.openx.container.FileLogger
import com.wy.openx.git.GitCommandFailureException
import com.wy.openx.git.GitCredential
import com.wy.openx.git.GitCredentialStore
import com.wy.openx.git.GitErrorMessage
import com.wy.openx.git.GitOutputTooLargeException
import com.wy.openx.git.GitRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 「凭据与署名」页的状态与操作。
 *
 * 两块内容共处一页（底部双 tab）：署名写容器 git config（user.name / user.email 全局、remote.origin.url
 * 项目级），凭据写 [GitCredentialStore]（credential.helper 读同一份文件），改完对下一次 push/pull 立即生效。
 */
class CredentialsViewModel(application: Application) : AndroidViewModel(application) {

    private companion object {
        const val TAG = "CredentialsViewModel"
    }

    data class UiState(
        val credentials: List<GitCredential> = emptyList(),
        val userName: String = "",
        val userEmail: String = "",
        val repoUrl: String = "",
        val notARepo: Boolean = false,
        val loading: Boolean = true,
        val busy: Boolean = false,
        val toast: String? = null
    )

    private val repository = GitRepository(application.applicationContext)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun consumeToast() { _state.update { it.copy(toast = null) } }

    /** 重读凭据列表与容器 git config。进入页面或任何写操作后调用。 */
    fun refresh() {
        viewModelScope.launch {
            GitCredentialStore.load()
            val credentials = GitCredentialStore.credentials.value
            val hint = repository.notReadyHint()
            if (hint != null) {
                _state.update { it.copy(loading = false, credentials = credentials, toast = hint) }
                return@launch
            }
            val isRepo = repository.isRepo()
            runCatching {
                Triple(
                    repository.getUserName(),
                    repository.getUserEmail(),
                    if (isRepo) repository.getRepoUrl() else ""
                )
            }.onSuccess { (name, email, url) ->
                _state.update {
                    it.copy(
                        loading = false,
                        credentials = credentials,
                        userName = name,
                        userEmail = email,
                        repoUrl = url,
                        notARepo = !isRepo
                    )
                }
            }.onFailure { e ->
                FileLogger.w(TAG, "读取 git 配置失败: ${e.message}")
                _state.update {
                    it.copy(loading = false, credentials = credentials, notARepo = !isRepo, toast = friendly(e))
                }
            }
        }
    }

    /**
     * 一次保存署名与仓库地址。
     *
     * 不能拆成两个方法串行调：两者都有 busy 守卫，第一个置 busy=true 后第二个会直接 return，
     * 用户改了名字和地址只存上其中一个。
     */
    fun saveIdentityAndUrl(name: String, email: String, url: String, saveUrl: Boolean) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                if (name.isNotBlank() && email.isNotBlank()) repository.setUserIdentity(name, email)
                if (saveUrl) repository.setRepoUrl(url)
                _state.update { it.copy(toast = "已保存", busy = false) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(toast = friendly(e), busy = false) }
            }
        }
    }

    fun saveCredential(credential: GitCredential) {
        viewModelScope.launch {
            runCatching { GitCredentialStore.save(credential) }
                .onSuccess { _state.update { it.copy(toast = "凭据已保存") } }
                .onFailure { e ->
                    FileLogger.e(TAG, "保存凭据失败", e)
                    _state.update { it.copy(toast = "保存失败：${e.message}") }
                }
            refresh()
        }
    }

    fun deleteCredential(id: String) {
        viewModelScope.launch {
            runCatching { GitCredentialStore.delete(id) }
                .onSuccess { _state.update { it.copy(toast = "凭据已删除") } }
                .onFailure { e ->
                    FileLogger.e(TAG, "删除凭据失败", e)
                    _state.update { it.copy(toast = "删除失败：${e.message}") }
                }
            refresh()
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is GitOutputTooLargeException -> e.output
        is GitCommandFailureException -> GitErrorMessage.friendly(e.output)
        else -> e.message ?: "操作失败"
    }
}
