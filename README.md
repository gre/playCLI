Play CLI
========

Play CLI defines helpers to deal with UNIX command with Play Framework iteratees.

* [Checkout the presentation slides](http://greweb.fr/playcli).
* [Checkout the scala API](http://greweb.fr/playcli/api).
* [Checkout Play CLI Examples application](http://github.com/gre/playCLI-examples).

Getting Started
---------------

Add this SBT dependency:

```scala
"fr.greweb" %% "playcli" % "0.1"
```
*Play CLI depends only on play-iteratee.*

Some random examples:

```scala
import playcli._
val logs = CLI.enumerate("tail -f aLogFile")
/* Ok.stream(logs) */

val colorQuantize = CLI.pipe("convert - -colors 14 png:-")
/* Ok.stream(anImageEnum &> colorQuantize) */

val scaleVideoHalf = CLI.pipe("ffmpeg -i pipe:0 -vf scale=iw/2:-1 -f avi pipe:1")
/* Ok.stream(streamEnum &> scaleVideoHalf) */

// Don't concatenate CMD string, but use the Seq syntax:
val logs = CLI.enumerate(Seq("tail", "-f", myFilePath))
/* Ok.stream(logs) */

// A consume example
val events : Enumerator[String] = …
events &> 
  Enumerator.map((events+"\n").map(_.toByte).toArray) |>>> 
  CLI.consume("externalLoggerCmd")

```