package co.uzzu.outcast

import co.uzzu.outcast.UnityPlayerTargetSdk34Modifier.Configuration
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.apache.bcel.classfile.ClassParser
import org.apache.bcel.classfile.JavaClass
import org.apache.bcel.classfile.Method
import org.apache.bcel.generic.ClassGen
import org.apache.bcel.generic.Instruction
import org.apache.bcel.generic.MethodGen

private const val UnityPlayerFilename = "com/unity3d/player/UnityPlayer.class"

private val targetOpcodes = "FIXME" // bb5959...
    .lowercase()
    .chunked(2)
    .map { it.toShort(16) }

private val constructorPredicate: (Method) -> Boolean = {
    it.name == "<init>" &&
        it.signature == "(Landroid/content/Context;Lcom/unity3d/player/IUnityPlayerLifecycleEvents;)V"
}

private data class RemovalInstructionRange(
    val start: Instruction,
    val end: Instruction,
)

class UnityPlayerTargetSdk34Modifier(
    private val config: Configuration,
) {

    data class Configuration(
        val input: File,
        val output: File,
    )

    fun modify() {
        config.input.useAsJarFile {
            val unityPlayerClass = unityPlayerClass()
            val constructor = unityPlayerClass.findConstructor()
            val unityPlayerClassGen = ClassGen(unityPlayerClass)
            val constructorGen = MethodGen(constructor, unityPlayerClassGen.className, unityPlayerClassGen.constantPool)
            val removalInstructionRange = constructorGen.findRemovalInstructionRange()

            constructorGen.instructionList.delete(removalInstructionRange.start, removalInstructionRange.end)
            constructorGen.setMaxStack()
            constructorGen.setMaxLocals()
            unityPlayerClassGen.removeMethod(constructor)
            unityPlayerClassGen.addMethod(constructorGen.method)

            val modifiedUnityPlayerClass = unityPlayerClassGen.javaClass
            modifiedUnityPlayerClass.fileName = UnityPlayerFilename

            JarOutputStream(FileOutputStream(config.output)).use { outputStream ->
                entries().asSequence().forEach { entry ->
                    if (entry.name == UnityPlayerFilename) {
                        val jarEntry = JarEntry(UnityPlayerFilename)
                        outputStream.putNextEntry(jarEntry)
                        val bytes = ByteArrayOutputStream().use {
                            modifiedUnityPlayerClass.dump(it)
                            it.toByteArray()
                        }
                        outputStream.write(bytes)
                    } else {
                        outputStream.putNextEntry(entry)
                        getInputStream(entry).use { inputStream ->
                            outputStream.write(inputStream.readBytes())
                        }
                    }
                    outputStream.closeEntry()
                }
            }
        }
    }

    private fun JavaClass.findConstructor(): Method =
        methods.find(constructorPredicate)
            ?: throw java.lang.IllegalStateException("constructor not found")

    private fun MethodGen.findRemovalInstructionRange(): RemovalInstructionRange {
        var instructionStart: Instruction? = null
        var instructionEnd: Instruction? = null
        var opcodeIndex: Int = 0

        for (handle in instructionList) {
            if (handle.instruction.opcode == targetOpcodes[opcodeIndex]) {
                if (opcodeIndex == targetOpcodes.lastIndex) {
                    instructionEnd = handle.instruction
                    break
                }
                if (opcodeIndex == 0) {
                    instructionStart = handle.instruction
                }
                opcodeIndex++
                continue
            }
            opcodeIndex = 0
        }

        return RemovalInstructionRange(
            start = checkNotNull(instructionStart),
            end = checkNotNull(instructionEnd),
        )
    }

    private fun <T> File.useAsJarFile(block: JarFile.() -> T): T =
        JarFile(this).use {
            block(it)
        }

    private fun JarFile.unityPlayerClass(): JavaClass =
        getInputStream(unityPlayerEntry()).use {
            val parser = ClassParser(it, UnityPlayerFilename)
            parser.parse()
        }

    private fun JarFile.unityPlayerEntry(): JarEntry =
        entries()
            .asSequence()
            .find {  it.name == UnityPlayerFilename }
            ?: throw IllegalStateException("Not found for class $UnityPlayerFilename")
}

fun main() {
    UnityPlayerTargetSdk34Modifier(
        Configuration(
            input = File("/path/to/debug/classes.jar"),
            output = File("/path/to/output/unity-classes.jar"),
        )
    ).modify()
}
