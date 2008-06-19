package play;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.log4j.Level;
import play.classloading.ApplicationClasses;
import play.classloading.ApplicationClassloader;
import play.db.DB;
import play.db.jpa.JPA;
import play.exceptions.UnexpectedException;
import play.libs.Files;
import play.vfs.VirtualFile;
import play.mvc.Router;
import play.templates.TemplateLoader;

public class Play {
    
    // Internal
    public static boolean started = false;
    public static String id;
    
    // Application
    public static File applicationPath = null;  
    public static File frameworkPath = null;
    public static ApplicationClasses classes = new ApplicationClasses();
    public static ApplicationClassloader classloader;
    public static List<VirtualFile> javaPath;
    public static List<VirtualFile> templatesPath;
    public static List<VirtualFile> routes;
    public static VirtualFile conf;
    public static Properties configuration;
    public static String applicationName;
    public static Long startedAt;
    
    public static void init(File root, String id) {
        Play.id = id;
        Play.started = false;
        Play.applicationPath = root;
        try {
            URI uri = Play.class.getResource("/play/version").toURI();
            if (uri.getScheme().equals("jar")) {
                String jarPath = uri.getSchemeSpecificPart().substring(5, uri.getSchemeSpecificPart().lastIndexOf("!"));
                frameworkPath = new File(jarPath).getParentFile().getParentFile().getAbsoluteFile();
            } else if (uri.getScheme().equals("file")) {
                frameworkPath = new File(uri).getParentFile().getParentFile().getParentFile().getParentFile();
            }
        } catch (Exception e) {
            throw new UnexpectedException(e);
        }
        start();
        Logger.info("Application '%s' is ready !", applicationName);
    }

    public static synchronized void start() {
        try {
            long start = System.currentTimeMillis();
            if(started) {
                Logger.debug("Reloading ...");
                stop();
            }
            Thread.currentThread().setContextClassLoader(Play.classloader);
            VirtualFile appRoot = VirtualFile.open(applicationPath);
            conf = appRoot.child("conf/application.conf");
            configuration = Files.readUtf8Properties(conf.inputstream());
            // Ok, check for instance specifics configuration
            Properties newConfiguration = new Properties();
            Pattern pattern = Pattern.compile("^%([a-zA-Z0-9_\\-]+)\\.(.*)$");
            for(Object key : configuration.keySet()) {
                Matcher matcher = pattern.matcher(key+"");
                if(matcher.matches()) {
                    String instance = matcher.group(1);
                    if(instance.equals(id)) {
                        newConfiguration.put(matcher.group(2), configuration.get(key));
                    }
                } else {
                    newConfiguration.put(key, configuration.get(key));
                }
            }
            configuration = newConfiguration;
            // XLog
            String logLevel = configuration.getProperty("application.log", "INFO");
            Logger.log4j.setLevel(Level.toLevel(logLevel));
            applicationName = configuration.getProperty("application.name", "(no name)");
            javaPath = new ArrayList<VirtualFile>();
            javaPath.add( appRoot.child("app"));
            templatesPath = new ArrayList<VirtualFile>();
            templatesPath.add(appRoot.child("app/views"));
            templatesPath.add(VirtualFile.open(new File(frameworkPath , "framework")));
            classloader = new ApplicationClassloader();
            routes=new ArrayList<VirtualFile>();
            routes.add(appRoot.child("conf/routes"));
            if (!configuration.getProperty("plugin.enable","disabled").equals("disabled"))
            	bootstrapPlugins();
            Router.load();
            TemplateLoader.cleanCompiledCache();
            DB.init();
            JPA.init();
            started = true;
            Logger.trace("%sms to start the application", System.currentTimeMillis()-start);            
            startedAt = System.currentTimeMillis();
        } catch(Exception e) {
            throw new UnexpectedException(e);
        }
    }
    
    public static synchronized void stop() {
        JPA.shutdown();
        started = false;
    }
   
    protected static synchronized void detectChanges() {
        try {
            if(conf.lastModified() > startedAt) {
                start();
                return;
            }
            Router.detectChanges();
            classloader.detectChanges();            
        } catch (UnsupportedOperationException e) {
            // We have to do a clean refresh
            start();
        }
    }
    
    public static void bootstrapPlugins () throws IOException {
    	File lib = new File (applicationPath,"lib");
    	File [] libs = lib.listFiles();
    	for (int i = 0; i < libs.length; i++) {
			if (libs[i].isFile()&&(libs[i].toString().endsWith(".zip") || libs[i].toString().endsWith(".jar")))
				addPlayApp(libs[i]);
			else if (isPlayApp(libs[i]))
				addPlayApp(libs[i]);
		} 
    	
    	String pluginPath = configuration.getProperty("plugin.path");
    	String[] pluginNames = configuration.getProperty("plugin.enable","").split(",");
    	if (pluginNames==null)
    		return;
    	for (int i = 0; i < pluginNames.length; i++) {
			String pluginName = pluginNames[i];
			File fl = new File (pluginName);
			if (fl.isFile() && (fl.toString().endsWith(".jar") || fl.toString().endsWith(".zip"))) {
				addPlayApp(fl);
			} else {
				if (fl.isAbsolute() && isPlayApp(fl))
					addPlayApp(fl);
				else {
					fl = new File (new File (pluginPath),fl.getPath());
					if (fl.isAbsolute() && isPlayApp(fl))
						addPlayApp(fl);
					else
						new RuntimeException (fl.getAbsolutePath()+" is not a play application/plugin !");
				}
			}
		}
    }
        
    public static void addPlayApp (File fl) {
    	VirtualFile root = VirtualFile.open(fl);
    	javaPath.add(root.child("app"));
    	templatesPath.add(root.child("app/views"));
    	routes.add(root.child("conf/routes"));
    	Logger.info("Plugin added: "+fl.getAbsolutePath());
    }
    
    public static boolean isPlayApp (File fl) {
    	if (! (new File(fl,"app").exists()))
    			return false;
    	if (! (new File(fl,"app").isDirectory()))
			return false;
    	if (! (new File(fl,"app/controllers/").exists()))
			return false;
    	if (! (new File(fl,"app/models/").exists()))
			return false;
    	if (! (new File(fl,"conf/routes").exists()))
			return false;
    	return true;
    }
    
    public static String getSecretKey() {
        return "SLD0FBVG78920DKMLKF39DJ92JO2";
    }
    
    public static VirtualFile getFile(String path) {
        return VirtualFile.open(applicationPath).child(path);
    }
}
