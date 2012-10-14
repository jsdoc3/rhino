Rhino 1.7R3 for JSDoc3
======================
This fork of Mozilla Rhino is intended for use with JSDoc 3 (https://github.com/jsdoc3/jsdoc). The
fork incorporates the changes described below.


JSDoc Comment Attachment
------------------------

The fork modifies the behavior of the parser as it relates to JSDoc comment attachment.
Specifically, the parser attaches comments to function calls when present.

Traditionally, there may have not been much reason to do so since JSDoc comments are intended
to be used to documentation generation and function calls do not provide any interfaces. However,
there are many JavaScript frameworks that use a factory pattern that uses a function call to create
classes.  For instance jQuery UI has a widget factory for creating plugins.  An example call might
look something like:

    $.widget("ui.mywidget", {
        options: {
            firstOption: true,
            secondOption: "Hello",
            thirdOption: null
        },
        _create: function() {
            this.element.html(this.options.secondOption + " World!");
        },
        destroy: function() {
            this.element.html();
        }
    });

Here, the first parameter provides a name for the class and the second provides a
prototype that will be used by the factory when creating the class.  It would be nice to be
able to add a JSDoc comment above the call to provide information about the mywidget class:

    /**
     * This widget takes a div element and makes it classic
     * @require UI Core
     * @require UI Widget
     * @example
     *      $('<div/>').mywidget({secondOption: "Hola"});
     */
    $.widget("ui.mywidget", ...);

Of course, there are workarounds like assigning the prototype to a variable first and commenting
that, but, let's face it, no one really likes to document and making folks change their
code just to do so would be asking a bit much.


Node.js/CommonJS Package Support
--------------------------------

Although Rhino 1.7R3 included support for loading CommonJS modules, it did not recognize CommonJS/
Node.js packages. This fork adds a new module provider with package support.

For example, suppose the module directory is organized as follows:

```
|-- foo.js
`-- bar
|   |-- package.json
|   `-- bar.js
`-- baz
    `-- index.js
```

In Rhino 1.7R3, if a JavaScript file requires the `foo`, `bar`, and `baz` modules, only the `foo`
module will be loaded successfully.

However, in this fork:

+ The `foo` module will still load successfully.
+ The `bar` module will load successfully as long as its `package.json` file includes a `main`
property that is set to `./bar.js`.
+ The `baz` module will load successfully, since the new module provider finds its `index.js` file.
