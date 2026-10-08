package bayern.kickner.kbang.build

import java.io.File

/**
 * The executable file [command] refers to, or null when there is none.
 *
 * A [command] containing a `/` is a path and used as is. A bare name is looked up in every directory of [path],
 * in order, like a shell does.
 *
 * @param path A `PATH`-style list of directories separated by `:`. Null means nothing to search.
 */
fun findExecutable(command: String, path: String?): File? {
    if (command.contains('/')) return File(command).takeIf { it.isExecutableFile() }
    return path.orEmpty().split(':')
        .filter { it.isNotBlank() }
        .map { File(it, command) }
        .firstOrNull { it.isExecutableFile() }
}

private fun File.isExecutableFile() = isFile && canExecute()
