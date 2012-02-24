Rhino 1.7R3 for JSDoc3
======================
This fork of rhino is intended for use with JSDoc3 (https://github.com/micmath/jsdoc)

The primary reason for the fork is to modify the behavior of the parser as it relates to JSDoc
comment attachment.  Specifically, it is to attach comments to function calls when present.
Traditionally, there may have not been much reason to do so since JSDoc comments are intended
to be used to documentation generation and function calls do not provide any interfaces.

However, there are many JavaScript frameworks that use a factory pattern that uses
a function call to create classes.  For instance jQuery UI has a widget factory for
creating plugins.  An example call might look something like:

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

Sooooo, here we are.  This fork just makes a very small change that lets function call nodes pickup the 
JSDoc comment if one is available when it gets created.  And the change is made to the 1.7R3
release, because whatever's in the head of Rhino right now (1.7R4Pre) does not work with JSDoc3.
I'll leave figuring that out to the JSDoc3 creator.  

