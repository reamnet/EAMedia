package mods.eamedia

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*

/** The pinned JNI input adapter reads GLFW's numeric constants by class name.
 * Generate just that compatibility table in the isolated worker jar. Never add
 * this class to Minecraft's classpath, or initialize a client GLFW/native window
 * on a dedicated server. The Windows adapter also asks for a few Set-1 scan codes. */
internal object WorkerInputConstants {
    fun bytecode(): ByteArray {
        val fields=linkedMapOf("GLFW_PRESS" to 1,"GLFW_RELEASE" to 0,"GLFW_REPEAT" to 2,
            "GLFW_MOUSE_BUTTON_1" to 0,"GLFW_MOUSE_BUTTON_2" to 1,"GLFW_MOUSE_BUTTON_3" to 2,
            "GLFW_MOD_SHIFT" to 1,"GLFW_MOD_CONTROL" to 2,"GLFW_MOD_ALT" to 4,"GLFW_MOD_SUPER" to 8)
        listOf("ESCAPE","ENTER","TAB","BACKSPACE","INSERT","DELETE","RIGHT","LEFT","DOWN","UP","PAGE_UP","PAGE_DOWN","HOME","END")
            .forEachIndexed { i,name->fields["GLFW_KEY_$name"]=256+i }
        listOf("CAPS_LOCK","SCROLL_LOCK","NUM_LOCK","PRINT_SCREEN","PAUSE").forEachIndexed { i,name->fields["GLFW_KEY_$name"]=280+i }
        for(i in 0..9)fields["GLFW_KEY_KP_$i"]=320+i
        fields["GLFW_KEY_KP_ENTER"]=335;fields["GLFW_KEY_LEFT_CONTROL"]=341
        val writer=ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        writer.visit(V17,ACC_PUBLIC or ACC_FINAL,"org/lwjgl/glfw/GLFW",null,"java/lang/Object",null)
        fields.forEach { (name,value)->writer.visitField(ACC_PUBLIC or ACC_STATIC or ACC_FINAL,name,"I",null,value).visitEnd() }
        val scans=sortedMapOf(259 to 14,260 to 338,280 to 58,281 to 70,282 to 69,283 to 311,284 to 325,
            322 to 80,324 to 75,326 to 77,328 to 72)
        val method=writer.visitMethod(ACC_PUBLIC or ACC_STATIC,"glfwGetKeyScancode","(I)I",null,null)
        method.visitCode();method.visitVarInsn(ILOAD,0)
        val fallback=Label();val labels=scans.keys.map { Label() }.toTypedArray()
        method.visitLookupSwitchInsn(fallback,scans.keys.toIntArray(),labels)
        scans.values.forEachIndexed { i,value->method.visitLabel(labels[i]);method.visitLdcInsn(value);method.visitInsn(IRETURN) }
        method.visitLabel(fallback);method.visitInsn(ICONST_M1);method.visitInsn(IRETURN);method.visitMaxs(0,0);method.visitEnd()
        writer.visitEnd();return writer.toByteArray()
    }
}
