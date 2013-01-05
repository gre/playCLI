package codecs

import play.api.libs.iteratee._

object OggChunker extends AudioChunker {

  // See http://www.digitalpreservation.gov/formats/fdd/fdd000026.shtml
  case class OggHeader (
    capturePattern: String, // 4 Bytes, (must be "OggS")
    streamStructureVersion: Byte, // 1 Byte
    headerTypeFlag: Byte, // 1 Byte
    granulePosition: Array[Byte], // 8 Bytes
    bitstreamSerialNumber: Integer, // 4 Bytes
    pageSequenceNumber: Integer, // 4 Bytes
    crcChecksum: Array[Byte], // 4 Bytes
    numberPageSegments: Short, // 1 Byte
    segmentTable: List[Short] // number_page_segments Bytes
  ) {
    lazy val headerSize = 27+numberPageSegments
    lazy val segmentsSize = segmentTable.foldLeft(0)(_+_)
    lazy val pageSize = headerSize+segmentsSize
    lazy val isValid = capturePattern == "OggS"
  }


  def byteAsUnsignedShort (byte: Byte): Short = (0xFF & byte).toShort

  def littleInt(bytes: Array[Byte]): Int = {
    bytes(0)<<24+
    bytes(1)<<16+
    bytes(2)<< 8+
    bytes(3)
  }

  val parseHeader: Iteratee[Byte, (Array[Byte], OggHeader)] = {
    (Enumeratee.take[Byte](27) &>> Iteratee.getChunks[Byte] map (_.toArray)) flatMap { firstBytes =>
      val numberPageSegments = firstBytes(26)
      (Enumeratee.take[Byte](numberPageSegments) &>> Iteratee.getChunks[Byte] map (_.toArray)) map { segments =>
        val h = OggHeader(
          firstBytes.slice(0, 4).map(_.toChar).mkString,
          firstBytes(4),
          firstBytes(5),
          firstBytes.slice(6, 14),
          littleInt(firstBytes.slice(14, 18)),
          littleInt(firstBytes.slice(18, 22)),
          firstBytes.slice(22, 26),
          byteAsUnsignedShort(numberPageSegments),
          segments.map(byteAsUnsignedShort(_)).toList
        )
        (firstBytes++segments, h)
      }
    }
  }

  def getPage (headerBytes: Array[Byte], header: OggHeader): Iteratee[Byte, Array[Byte]] = {
      if (header.isValid) {
        Enumeratee.heading(Enumerator(headerBytes : _*)) ><>
        Enumeratee.take[Byte](header.pageSize) &>>
        Iteratee.getChunks[Byte] map (_.toArray)
      }
      else {
        Error("Invalid header "+header, Input.Empty)
      }
  }

  /*
  val oggHeader = Array(0x4f, 0x67, 0x67, 0x53, 0x00, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x21, 0xcf, 0x1a, 0x1e, 0x00,
    0x00, 0x00, 0x00, 0x8e, 0x47, 0x13, 0xc2, 0x01, 0x1e, 0x01, 0x76, 0x6f, 0x72, 0x62, 0x69, 0x73, 0x00, 0x00, 0x00, 0x00, 0x02, 0x44,
    0xac, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xfa, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xb8, 0x01).map(_.toByte)
  */

  override def apply (stream: Enumerator[Byte]): (Enumerator[Array[Byte]], Enumerator[Array[Byte]]) = {
    val headers = Enumerator[Array[Byte]](/*oggHeader*/)
    val chunkedStream = stream &> Enumeratee.grouped(parseHeader flatMap { case (bytes, header) => getPage(bytes, header) })
    (headers, chunkedStream)
  }
}

