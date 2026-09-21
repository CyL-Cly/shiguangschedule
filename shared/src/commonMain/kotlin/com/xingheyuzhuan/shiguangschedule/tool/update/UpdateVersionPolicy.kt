package com.xingheyuzhuan.shiguangschedule.tool.update

/** 更新比对决策。 */
sealed interface UpdateDecision {
    /** 清单拉取失败 / 无效，提示无法连接更新服务器。 */
    data object Error : UpdateDecision
    /** 已是最新版本。 */
    data object UpToDate : UpdateDecision
    /** 存在新版本。 */
    data class Available(val manifest: UpdateManifest) : UpdateDecision
}

object UpdateVersionPolicy {
    /** remote 为 null（网络失败/解析失败）或清单无效 → Error；远端更新 → Available；否则 UpToDate。 */
    fun evaluate(remote: UpdateManifest?, localVersionCode: Long): UpdateDecision = when {
        remote == null || !remote.isValid() -> UpdateDecision.Error
        remote.isNewerThan(localVersionCode) -> UpdateDecision.Available(remote)
        else -> UpdateDecision.UpToDate
    }
}
