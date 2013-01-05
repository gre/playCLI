package codecs

import play.api.libs.iteratee._

trait AudioChunker {
  /**
   * extract from a stream the audio headers and a valid chunked stream (paged)
   * @return (beginning headers, chunked stream)
   */
  def apply (stream: Enumerator[Byte]): (Enumerator[Array[Byte]], Enumerator[Array[Byte]])
}

