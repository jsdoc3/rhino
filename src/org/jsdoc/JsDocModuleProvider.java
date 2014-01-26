package org.jsdoc;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.jsdoc.SourceReader;
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider;
import org.mozilla.javascript.json.JsonParser;
import org.mozilla.javascript.json.JsonParser.ParseException;
import org.mozilla.javascript.commonjs.module.provider.ModuleSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;


/**
 * An extension of Rhino's UrlModuleSourceProvider that supports Node.js/CommonJS packages.
 * @author Jeff Williams
 */
public class JsDocModuleProvider extends UrlModuleSourceProvider {
	private static final String JS_EXTENSION = ".js";
	private static final String PATH_SEPARATOR = "/";
	private static final String PACKAGE_FILE = "package.json";
	private static final String MODULE_INDEX = "index" + JS_EXTENSION;
	private static final String SUBMODULE_DIRECTORY = "node_modules";

	public JsDocModuleProvider(Iterable<URI> privilegedUris, Iterable<URI> fallbackUris) {
		super(privilegedUris, fallbackUris);
	}

	@Override
	protected ModuleSource loadFromPathList(String moduleId, Object validator, Iterable<URI> paths)
		throws IOException, URISyntaxException {
		if (paths == null) {
			return null;
		}

		for (URI path : paths) {
			URI moduleUri;

			// Try to process the module ID as a URI, then as a filepath
			try {
				moduleUri = path.resolve(moduleId);
			}
			catch(IllegalArgumentException e) {
				// If a directory contains foo/ and foo.js, and the code says "require('foo')",
				// we want foo.js
				File modulePath = new File(moduleId);
				File modulePathJs = moduleId.endsWith(JS_EXTENSION) ? modulePath :
					new File(moduleId + JS_EXTENSION);
				if (modulePath.isDirectory() && modulePathJs.isFile()) {
					modulePath = modulePathJs;
				}

				if (modulePath.isAbsolute()) {
					moduleUri = modulePath.toURI();
				} else {
					moduleUri = new File(new File(path).getAbsolutePath(), moduleId).toURI();
				}
			}

			final ModuleSource moduleSource = loadFromUri(moduleUri, path, validator);
			if (moduleSource != null) {
				return moduleSource;
			}
		}
		return null;
	}

	@Override
	protected ModuleSource loadFromUri(URI uri, URI base, Object validator)
		throws IOException, URISyntaxException {
		try {
			// Start by searching for the module in the usual locations.
			URI moduleUri = getModuleUri(uri, base);
			ModuleSource source = loadFromActualUri(moduleUri, base, validator);

			// For compatibility, we support modules without extension, or IDs
			// with explicit extension.
			return source != null ? source : loadFromActualUri(uri, base, validator);
		} catch (Exception e) {
			return null;
		}
	}

	private URI getCurrentModuleUri() throws URISyntaxException {
		// Depends on a hack in Require
		return new File(System.getProperty("user.dir")).toURI();
	}

	private URI getModuleUri(URI uri, URI base)
		throws SecurityException, IOException, ParseException, URISyntaxException {
		return getModuleUri(uri, base, true);
	}

	private URI getModuleUri(URI uri, URI base, boolean checkForSubmodules)
		throws SecurityException, IOException, ParseException, URISyntaxException {
		URI moduleUri = null;
		String uriString = uri.toString();

		// Add a ".js" extension if necessary
		URI jsUri = ensureJsExtension(uri);

		URI packageUri = new URI(uriString + PATH_SEPARATOR + PACKAGE_FILE);
		URI indexUri = new URI(uriString + PATH_SEPARATOR + MODULE_INDEX);

		// Check for the following, in this order:
		// 1. The file jsFile.
		// 2. The "main" property of the JSON file packageFile.
		// 3. The file indexFile.
		// 4. A submodule of the current module that matches #1, #2, or #3 (if checkForSubmodules
		//    is true).
		if (new File(jsUri).isFile()) {
			moduleUri = jsUri;
		} 

		if (moduleUri == null && new File(packageUri).isFile()) {
			moduleUri = getPackageMain(packageUri);
		}

		if (moduleUri == null && new File(indexUri).isFile()) {
			moduleUri = indexUri;
		}

		if (moduleUri == null && checkForSubmodules) {
			moduleUri = getSubmoduleUri(uri, base);
		}

		return moduleUri;
	}

	private URI getSubmoduleUri(URI uri, URI base)
		throws SecurityException, IOException, ParseException, URISyntaxException {
		URI submoduleUri = null;
		String currentModule = getCurrentModuleUri().toString();

		// Find the nearest parent module, if any.
		int submoduleParentIndex = currentModule.lastIndexOf(SUBMODULE_DIRECTORY + PATH_SEPARATOR);
		if (submoduleParentIndex != -1) {
			int parentIndex = currentModule.indexOf(PATH_SEPARATOR, submoduleParentIndex +
				SUBMODULE_DIRECTORY.length() + 1);
			if (parentIndex != -1) {
				String parentModule = currentModule.substring(0, parentIndex);
				String submoduleDir = parentModule + PATH_SEPARATOR + SUBMODULE_DIRECTORY;

				URI submoduleSearchUri = new URI(submoduleDir + PATH_SEPARATOR +
					base.relativize(uri).toString());
				submoduleUri = getModuleUri(submoduleSearchUri, base, false);
			}
		}

		return submoduleUri;
	}

	private URI getPackageMain(URI packageUri) throws IOException, ParseException {
		return getPackageMain(new File(packageUri));
	}

	private URI getPackageMain(File packageFile) throws IOException, ParseException {
		NativeObject packageJson = parsePackageFile(packageFile);
		String mainFile = (String) packageJson.get("main");
		if (mainFile != null) {
			mainFile = ensureJsExtension(mainFile);
			return packageFile.toURI().resolve(mainFile);
		} else {
			return null;
		}
	}

	private URI ensureJsExtension(URI uri) throws URISyntaxException {
		String str = uri.toString();
		return new URI(ensureJsExtension(str));
	}

	private String ensureJsExtension(String str) {
		if (!str.endsWith(JS_EXTENSION)) {
			str += JS_EXTENSION;
		}
		return str;
	}

	private NativeObject parsePackageFile(File packageFile) throws IOException, ParseException {
		String packageJson = SourceReader.readFileOrUrl(packageFile.toString(), true, "UTF-8").
			toString();

		Context cx = Context.enter();
		JsonParser parser = new JsonParser(cx, cx.initStandardObjects());
		NativeObject json = (NativeObject) parser.parseValue(packageJson);
		return json;
	}
}
