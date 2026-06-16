@file:Suppress("PackageDirectoryMismatch")
package chat.simplex.common.platform

import java.nio.ByteBuffer

/**
 * JNI bindings to libsimplex.so. Lifted as-is (package + signatures) from
 * simplex-chat/apps/multiplatform/common/src/commonMain/kotlin/chat/simplex/common/platform/Core.kt
 * because the symbol names baked into the .so resolve to this exact
 * package path.
 *
 * Source: https://github.com/simplex-chat/simplex-chat — AGPL-3.0-only.
 * See ATTRIBUTION-SimpleX.md at the repo root.
 */

external fun initHS()
external fun pipeStdOutToSocket(socketName: String): Int

typealias ChatCtrl = Long

external fun chatMigrateInit(dbPath: String, dbKey: String, confirm: String): Array<Any>
external fun chatCloseStore(ctrl: ChatCtrl): String
external fun chatSendCmdRetry(ctrl: ChatCtrl, msg: String, retryNum: Int): String
external fun chatSendRemoteCmdRetry(ctrl: ChatCtrl, rhId: Int, msg: String, retryNum: Int): String
external fun chatRecvMsg(ctrl: ChatCtrl): String
external fun chatRecvMsgWait(ctrl: ChatCtrl, timeout: Int): String
external fun chatParseMarkdown(str: String): String
external fun chatParseServer(str: String): String
external fun chatParseUri(str: String, safe: Int): String
external fun chatPasswordHash(pwd: String, salt: String): String
external fun chatValidName(name: String): String
external fun chatJsonLength(str: String): Int
external fun chatWriteFile(ctrl: ChatCtrl, path: String, buffer: ByteBuffer): String
external fun chatReadFile(path: String, key: String, nonce: String): Array<Any>
external fun chatEncryptFile(ctrl: ChatCtrl, fromPath: String, toPath: String): String
external fun chatDecryptFile(fromPath: String, key: String, nonce: String, toPath: String): String
