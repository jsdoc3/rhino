package org.jsdoc;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import org.mozilla.javascript.commonjs.module.provider.UrlModuleSourceProvider;
import org.mozilla.javascript.json.JsonParser;
import org.mozilla.javascript.json.JsonParser.ParseException;
import org.mozilla.javascript.commonjs.module.provider.ModuleSource;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.NativeObject;
import org.mozilla.javascript.tools.SourceReader;


/**
 * An extension of Rhino's UrlModuleSourceProvider that supports Node.js/CommonJS packages.
 * @author Jeff Williams
 */
public class JsDocModuleProvider extends UrlModuleSourceProvider {
	private static final String JS_EXTENSION = ".js";
	private static final String PACKAGE_FILE = "package.json";
	private static final String MODULE_INDEX = "index" + JS_EXTENSION;

	public JsDocModuleProvider(Iterable<URI> privilegedUris, Iterable<URI> fallbackUris) {
		super(privilegedUris, fallbackUris);
	}

	@Override
	protected ModuleSource loadFromUri(URI uri, URI base, Object validator)
		throws IOException, URISyntaxException {
		String uriString = uri.toString();
		if (!uriString.endsWith(JS_EXTENSION)) {
			uriString += JS_EXTENSION;
		}

		File jsFile = new File(new URI(uriString));
		File packageFile = new File(new URI(uri.toString() + File.separator + PACKAGE_FILE));
		File indexFile = new File(new URI(uri.toString() + File.separator + MODULE_INDEX));

		try {
			URI moduleUri = getModuleUri(jsFile, packageFile, indexFile);
			return loadFromActualUri(moduleUri, base, validator);
		} catch (Exception e) {
			return null;
		}
	}

	private URI getModuleUri(File jsFile, File packageFile, File indexFile)
		throws SecurityException, IOException, ParseException {

		// Check for the following, in this order:
		// 1. The file jsFile.
		// 2. The "main" property of the JSON file packageFile.
		// 3. The file indexFile.
		if (jsFile.isFile()) {
			return jsFile.toURI();
		} 

		if (packageFile.isFile()) {
			URI packageMain = getPackageMain(packageFile);
			if (packageMain != null) {
				return packageMain;
			}
		}

		if (indexFile.isFile()) {
			return indexFile.toURI();
		}

		// couldn't find the module URI
		return null;
	}

	private URI getPackageMain(File packageFile) throws IOException, ParseException {
		NativeObject packageJson = parsePackageFile(packageFile);
		String mainFile = (String) packageJson.get("main");
		if (mainFile != null) {
			return packageFile.toURI().resolve(mainFile);
		} else {
			return null;
		}
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
