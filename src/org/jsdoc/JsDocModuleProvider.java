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
				File modulePath = new File(moduleId);
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
		// Add a ".js" extension if necessary
		URI jsUri = ensureJsExtension(uri);

		URI packageUri = new URI(uri.toString() + PATH_SEPARATOR + PACKAGE_FILE);
		URI indexUri = new URI(uri.toString() + PATH_SEPARATOR + MODULE_INDEX);

		try {
			URI moduleUri = getModuleUri(jsUri, packageUri, indexUri);
			ModuleSource source = loadFromActualUri(moduleUri, base, validator);
			// For compatibility, we support modules without extension, or IDs
			// with explicit extension.
			return source != null ? source : loadFromActualUri(uri, base, validator);
		} catch (Exception e) {
			return null;
		}
	}

	private URI getModuleUri(URI jsUri, URI packageUri, URI indexUri)
		throws SecurityException, IOException, ParseException {
		// Check for the following, in this order:
		// 1. The file jsFile.
		// 2. The "main" property of the JSON file packageFile.
		// 3. The file indexFile.
		if (new File(jsUri).isFile()) {
			return jsUri;
		} 

		if (new File(packageUri).isFile()) {
			URI packageMain = getPackageMain(packageUri);
			if (packageMain != null) {
				return packageMain;
			}
		}

		if (new File(indexUri).isFile()) {
			return indexUri;
		}

		// couldn't find the module URI
		return null;
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
