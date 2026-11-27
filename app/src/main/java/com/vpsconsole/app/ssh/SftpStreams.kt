package com.vpsconsole.app.ssh

import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet

/**
 * Chunked SFTP streaming helpers.
 *
 * Android document providers can represent very large files, so transfers must
 * never be materialized in the heap or copied through cacheDir first.
 */
object SftpStreams {
    private const val CHUNK_SIZE = 16 * 1024

    fun upload(input: InputStream, sftp: SFTPClient, remotePath: String) {
        val modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        val remote = sftp.getSFTPEngine().open(remotePath, modes)
        remote.use { file ->
            val buffer = ByteArray(CHUNK_SIZE)
            var offset = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                file.write(offset, buffer, 0, read)
                offset += read
            }
        }
    }

    fun download(sftp: SFTPClient, remotePath: String, output: OutputStream) {
        val remote = sftp.getSFTPEngine().open(remotePath, EnumSet.of(OpenMode.READ))
        remote.use { file ->
            val buffer = ByteArray(CHUNK_SIZE)
            var offset = 0L
            while (true) {
                val read = file.read(offset, buffer, 0, buffer.size)
                if (read <= 0) break
                output.write(buffer, 0, read)
                offset += read
            }
        }
        output.flush()
    }
}
