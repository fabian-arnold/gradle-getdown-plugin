package bundles

import groovy.text.GStringTemplateEngine

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.CopySpec
import org.gradle.api.file.FileCopyDetails
import org.gradle.api.file.RelativePath
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Tar
import org.gradle.api.tasks.bundling.Zip

class GetdownPlugin implements Plugin<Project> {
	static final String PLUGIN_NAME = "getdown"
	static final String GROUP = "getdown-bundles"
	static final GStringTemplateEngine engine = new GStringTemplateEngine()

	void apply(Project project) {
		project.plugins.apply(JavaPlugin)
		project.configurations {
			getdown
		}
		project.dependencies {
			getdown 'com.threerings:getdown:1.3.1'
		}
		def cfg = project.extensions.create("getdown", GetdownPluginExtension)
		cfg.title = project.name
		cfg.version = "app"
		cfg.dest = project.file("${project.buildDir}/getdown")
		cfg.destVersion = new File(cfg.dest, cfg.version)
		cfg.tmplGetdownTxt = read("bundles/getdown.txt")
		cfg.tmplScriptUnix = read("bundles/launch")
		cfg.tmplLaunch4j = read("bundles/launch4j-config.xml")
		cfg.tmplScriptWindows = read("bundles/launch.vbs")
		String v = System.properties['launch4jCmd']
		if (v != null) {
			cfg.launch4jCmd = engine.createTemplate(v).make(['System' : System]).toString()
			//cfg.launch4jCmd = System.properties['launch4jCmd']
		}
		cfg.jreCacheDir = project.file("${System.properties['user.home']}/.cache/jres")
		project.afterEvaluate {
			project.task(type: JavaExec, 'run') {
				description = "Runs this project as a JVM application"
				group GROUP
				workingDir cfg.destVersion
				classpath project.configurations.runtime //project.sourceSets.main.runtimeClasspath
				jvmArgs cfg.jvmArgs
				main cfg.mainClassName
			}
			cfg.platforms.collect { platform ->
				//see http://www.oracle.com/technetwork/java/javase/jre-8-readme-2095710.html
				project.task(type: GetJreTask, "getJre_${platform.durl}") {
					description = "download + repackage jre(s) into cache dir (${cfg.jreCacheDir}) for platform ${platform.durl}"
					group GROUP
					platforms = [platform]
					dir = cfg.jreCacheDir
					version = cfg.jreVersion
				}
			}
			//see http://www.oracle.com/technetwork/java/javase/jre-8-readme-2095710.html
			project.task(type: GetJreTask, "getJres") {
				description = "download + repackage jre(s) into cache dir (${cfg.jreCacheDir}) for all platforms"
				group GROUP
				platforms = cfg.platforms
				dir = cfg.jreCacheDir
				version = cfg.jreVersion
			}
//			project.task('getJres') {
//				description = "download + repackage jre(s) into cache dir (${cfg.jreCacheDir}) for all platforms"
//				group GROUP
//				dependsOn {
//					cfg.platforms.collect {platform -> "getJre_${platform.durl}" }
//				}
//			}
			project.task('makeGetdownTxt') {
				description = 'create the file getdown.txt'
				group GROUP
				doLast {
					def f = new File(cfg.destVersion, "getdown.txt")
					def binding = ["project": project, "cfg": cfg, "JreTools": JreTools]
					def str = engine.createTemplate(cfg.tmplGetdownTxt).make(binding).toString()
					f.write(str)
				}
			}
			project.task(type: JavaExec, 'makeDigest') {
				description = 'create the file digest.txt from getdown.txt + files'
				group GROUP
				dependsOn 'makeGetdownTxt'
				workingDir cfg.destVersion
				classpath project.configurations.getdown
				main 'com.threerings.getdown.tools.Digester'
				args '.'
			}
			project.task('makeLauncherUnix') {
				description = "create the launcher script for unix (linux)"
				group GROUP
				doLast {
					def f = new File(cfg.dest, "launch")
					def str = cfg.tmplScriptUnix.toString()
					f.write(str)
					ant.chmod(file: f, perm: "ugo+rx")
				}
			}
			if (cfg.launch4jCmd != null && cfg.launch4jCmd.trim().length() > 0 ) {
				project.task(type: Exec, 'makeLauncherWindows') {
					description = "create the launcher for windows (via launch4j to generate an .exe file)"
					group = GROUP
					commandLine new File(cfg.launch4jCmd).getCanonicalPath(), project.file("${cfg.dest}-tmp/launch4j-config.xml")
					workingDir cfg.dest
					doFirst {
						def getdownJar = project.configurations.getdown.resolve().iterator().next().getName()
						def binding = ["project": project, "cfg": cfg
							, 'outfile' : new File(cfg.dest, "launch.exe").getCanonicalPath()
							, jar : "${cfg.version}/${getdownJar}"
							, title: cfg.title
							, icon : new File(cfg.destVersion, "favicon.ico").getCanonicalPath()
						]
						def str = engine.createTemplate(cfg.tmplLaunch4j).make(binding).toString()
						def f = project.file("${cfg.dest}-tmp/launch4j-config.xml")
						f.getParentFile().mkdirs()
						f.write(str)
						cfg.dest.mkdirs()
					}
				}
			} else {
				project.task('makeLauncherWindows') {
					description = "create the launcher for windows (create a VBS script)"
					group GROUP
					doLast {
						logger.info("no launch4jCmd defined, then use tmplScriptWindows")
						def f = new File(cfg.dest, "launch.vbs")
						def str = cfg.tmplScriptWindows.toString()
						f.write(str)
					}
				}
			}
			project.task('makeLaunchers') {
				description = "create the launchers for unix and windows"
				dependsOn project.makeLauncherUnix, project.makeLauncherWindows
			}
			cfg.distSpec = configureDistSpec(project, cfg.version)
			project.task(type: Sync, "copyDist") {
				description = "copy src/dist + jres into ${cfg.dest}"
				group GROUP
				dependsOn project.assemble
				with cfg.distSpec
				into cfg.dest
			}
			project.copyDist.mustRunAfter(project.getJres)

			project.task('assembleApp'){
				description = "assemble the full app (getdown ready) into ${cfg.dest}"
				group GROUP
				dependsOn project.copyDist, project.makeGetdownTxt, project.makeDigest, project.makeLaunchers
			}
			def taskBundleName = "bundle"
			def bundlesDir = new File(cfg.dest, "bundles")
			project.task(type: Tar, "${taskBundleName}_0") {
				description = "bundle the application into .tgz without jre"
				group GROUP
				dependsOn project.assembleApp
				compression = 'gzip'
				destinationDir = bundlesDir
				version = cfg.version
				into(project.name) {
					from (cfg.dest) {
						exclude bundlesDir.getName()
						exclude 'jres'
					}
				}
			}
			cfg.platforms.collect { platform ->
				//def jreArchive = project.file("${cfg.dest}/jres/${JreTools.findJreJarName(cfg.jreVersion, platform)}")
				def jreArchive = project.getJres.cachePath(platform)
				def bundleSpec = project.copySpec {
					into(project.name) {
						from (cfg.dest) {
							exclude 'bundles'
							exclude 'jres'
						}
						into('jre') {
							from project.zipTree(jreArchive)
							// remove java_vm prefix
							eachFile { FileCopyDetails fcp ->
								def filter = "java_vm/"
								def pathString = fcp.relativePath.pathString
								def pos = fcp.relativePath.pathString.indexOf("java_vm/")
								if (pos > -1) {
									def pathsegments = pathString.substring(0, pos) + pathString.substring(pos + filter.length())
									fcp.relativePath = new RelativePath(!fcp.file.isDirectory(), pathsegments)
									if (pathsegments.indexOf('bin/') > -1 || pathsegments.indexOf('launch') > -1) {
										fcp.setMode(0755)
									}
								} else {
									fcp.exclude()
								}
							}
							includeEmptyDirs = false
						}
					}
				}
				if (platform.durl.indexOf("windows") < 0) {
					project.task(type: Tar, "${taskBundleName}_${platform.durl}") {
						description = "bundle the application into .tgz with jre for ${platform.durl}"
						group GROUP
						dependsOn "getJre_${platform.durl}", project.assembleApp
						compression = 'gzip'
						destinationDir = bundlesDir
						version = cfg.version
						classifier = platform.durl
						with bundleSpec
					}
				} else {
					project.task(type: Zip, "${taskBundleName}_${platform.durl}") {
						description = "bundle the application into .zip with jre for ${platform.durl}"
						group GROUP
						dependsOn "getJre_${platform.durl}", project.assembleApp
						destinationDir = bundlesDir
						version = cfg.version
						classifier = platform.durl
						with bundleSpec
					}
				}
			}
			project.task("${taskBundleName}s"){
				description = "generate all bundles"
				group GROUP
				dependsOn "${taskBundleName}_0"
				dependsOn {
					cfg.platforms.collect {platform -> "${taskBundleName}_${platform.durl}" }
				}
			}
		}
	}

	def assembleApp(project) {
		def cfg = project.getdown
		//project.file(cfg.dest).mkdirs()
		//project.file(cfg.destVersion).mkdirs()

	}

	CopySpec configureDistSpec(project, version) {
		def jar = project.tasks[JavaPlugin.JAR_TASK_NAME]
		//def jres = project.tasks.findAll{it.name.startsWith('getJre_')}.collect{it.jres}.flatten
		def jres = project.tasks['getJres'].jres
		def distSpec = project.copySpec {}
		distSpec.with {
			into(version){
				from(project.file("src/dist"))
				from(project.configurations.getdown)
				into("lib") {
					from(jar)
					from(project.configurations.runtime)
				}
			}
			into("jres") {
				from(jres)
			}
		}
		distSpec
	}

	String read(String rsrc) {
		return Thread.currentThread().getContextClassLoader().getResourceAsStream(rsrc).text
	}
}
