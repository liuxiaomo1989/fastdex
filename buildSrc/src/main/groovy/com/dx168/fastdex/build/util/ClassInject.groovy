package com.dx168.fastdex.build.util

import org.gradle.api.Project
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 source class:
 ''''''
 public class MainActivity {

 }
 '''''

 dest class:
 ''''''
 import com.dx168.fastdex.runtime.antilazyload.AntilazyLoad;

 public class MainActivity {
     public MainActivity() {
        System.out.println(Antilazyload.str);
     }
 }
 ''''''
 * 代码注入，往所有的构造方法中添加对com.dx168.fastdex.runtime.antilazyload.AntilazyLoad的依赖
 * Created by tong on 17/10/3.
 */
public class ClassInject implements Opcodes {
    /**
     * 把所有的项目代码的class都做注入并且生成新的jar包
     * @param project
     * @param applicationVariant
     * @param combinedJar           输入jar包
     * @param outJar                输出jar包
     */
    public static final void injectJar(Project project,Object applicationVariant,File combinedJar, File outJar) {
        String variantName = applicationVariant.name.capitalize()
        //unzip merged.jar
        File unzipDir = new File(FastdexUtils.getBuildDir(project,variantName),"merged")
        project.copy {
            from project.zipTree(combinedJar)
            into unzipDir
        }
        Set<String> sourceSetJavaFiles = scanNeedInjectClass(project,applicationVariant)
        //project.logger.error("==fastdex sourceSetJavaFiles: " + sourceSetJavaFiles)

        File classesDir = new File(FastdexUtils.getBuildDir(project,variantName),Constant.FASTDEX_CLASSES_DIR)
        FileUtils.ensumeDir(classesDir)
        Files.walkFileTree(unzipDir.toPath(),new ClassInjectFileVisitor(project,sourceSetJavaFiles,unzipDir.toPath(),classesDir.toPath()))
        project.logger.error("==fastdex inject complete: ${outJar}")
        project.ant.zip(baseDir: classesDir, destFile: outJar)

        FileUtils.deleteDir(unzipDir)
        FileUtils.deleteDir(classesDir)
    }

    private static final void injectClass(File source, File dest) {
        byte[] classBytes = FileUtils.readContents(source)
        ClassReader classReader = new ClassReader(classBytes);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_MAXS);
        ClassVisitor classVisitor = new MyClassVisitor(classWriter);
        classReader.accept(classVisitor, Opcodes.ASM5);


        File parent = dest.getParentFile();
        if (parent != null && (!parent.exists())) {
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(dest);
        fos.write(classWriter.toByteArray());
        fos.close();
    }

    private static class ClassInjectFileVisitor extends SimpleFileVisitor<Path> {
        Project project
        Set<String> sourceSetJavaFiles
        Path scanPath
        Path outputPath

        ClassInjectFileVisitor(Project project,Set<String> sourceSetJavaFiles,Path scanPath,Path outputPath) {
            this.project = project
            this.sourceSetJavaFiles = sourceSetJavaFiles
            this.scanPath = scanPath
            this.outputPath = outputPath
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (!file.toFile().getName().endsWith(Constant.CLASS_SUFFIX)) {
                return FileVisitResult.CONTINUE;
            }
            Path relativePath = scanPath.relativize(file)
            Path classFilePath = outputPath.resolve(relativePath)

            if (matchSourceSetJavaFile(relativePath) && !isBalckList(relativePath.toString())) {
                project.logger.error("==fastdex inject: " + file)
                ClassInject.injectClass(file.toFile(),classFilePath.toFile())
            }
            else {
                FileUtils.copyFileUsingStream(file.toFile(),classFilePath.toFile())
            }
            return FileVisitResult.CONTINUE;
        }

        boolean isBalckList(String classFile) {
            //TODO
            return false
        }

        boolean matchSourceSetJavaFile(Path relativePath) {
            //relativePath  like   com.dx168.fastdex.sample.MainActivity.class
            String className = relativePath.toString()
            className = className.substring(0,className.length() - Constant.CLASS_SUFFIX.length())
            //className => com.dx168.fastdex.sample.MainActivity
            boolean result = sourceSetJavaFiles.contains(className)
            return result
        }
    }

    /**
     *扫描所有的项目代码(sourceSet、app/build/generated)
     */
    private static Set<String> scanNeedInjectClass(Project project,Object applicationVariant) {
        /**
         source dir
         ├── com
         │   └── dx168
         │       └── fastdex
         │           └── sample
         │               ├── Application.class
         │               ├── BuildConfig.class
         │               └── MainActivity.class
         └── rx
         ├── Observable.class
         └── Scheduler.class

         result =>
         com.dx168.fastdex.sample.Application
         com.dx168.fastdex.sample.BuildConfig
         com.dx168.fastdex.sample.MainActivity
         rx.Observable
         rx.Scheduler
         */
        Set<String> result = new HashSet<>();
        List<String> srcLists = new ArrayList<>()
        for (String srcDir : project.android.sourceSets.main.java.srcDirs) {
            srcLists.add(srcDir);
        }

        //app/build/generated/source/buildConfig/${variantStr}
        File buildConfigDir = applicationVariant.getVariantData().getScope().getBuildConfigSourceOutputDir()
        if (FileUtils.dirExists(buildConfigDir.getAbsolutePath())) {
            srcLists.add(buildConfigDir.getAbsolutePath())
        }

        //处理butterknife的输出路径 app/build/generated/source/apt/${variantStr}
        File aptDir = new File(new File(buildConfigDir.getParentFile().getParentFile(),"apt"),buildConfigDir.getName())
        if (FileUtils.dirExists(aptDir.getAbsolutePath())) {
            srcLists.add(aptDir.getAbsolutePath())
        }

        for (String srcDir : srcLists) {
            project.logger.error("==fastdex sourceSet: " + srcDir)

            Path srcDirPath = new File(srcDir).toPath()
            Files.walkFileTree(srcDirPath,new SimpleFileVisitor<Path>(){
                @Override
                FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.toFile().getName().endsWith(Constant.JAVA_SUFFIX)) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path relativePath = srcDirPath.relativize(file)

                    String className = relativePath.toString()
                    className = className.substring(0,className.length() - Constant.JAVA_SUFFIX.length())
                    result.add(className)
                    return FileVisitResult.CONTINUE
                }
            })
        }
        return result
    }

    private static class MyClassVisitor extends ClassVisitor {
        public MyClassVisitor(ClassVisitor classVisitor) {
            super(Opcodes.ASM5, classVisitor);
        }

        @Override
        public MethodVisitor visitMethod(int access,
                                         String name,
                                         String desc,
                                         String signature,
                                         String[] exceptions) {
            if ("<init>".equals(name)) {
                //get origin method
                MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
                //System.out.println(name + " | " + desc + " | " + signature);
                MethodVisitor newMethod = new AsmMethodVisit(mv);
                return newMethod;
            } else {
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        }
    }

    static class AsmMethodVisit extends MethodVisitor {
        public AsmMethodVisit(MethodVisitor mv) {
            super(Opcodes.ASM5, mv);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                mv.visitFieldInsn(GETSTATIC, "com/dx168/fastdex/runtime/antilazyload/AntilazyLoad", "str", "Ljava/lang/String;");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
            }
            super.visitInsn(opcode);
        }
    }
}
