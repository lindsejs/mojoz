package xsdgen

import java.io.InputStream
import java.util.ArrayList
import scala.Array.canBuildFrom
import scala.collection.JavaConversions.asScalaBuffer
import scala.reflect.BeanProperty
import scala.xml.PrettyPrinter
import org.yaml.snakeyaml.Yaml
import java.io.File
import scala.io.Source

case class XsdTypeDef(
  name: String,
  fields: Seq[XsdTypeDef])

object XsdGen {
  def recursiveListFiles(f: File): Array[File] = {
    val these = f.listFiles
    these ++ these.filter(_.isDirectory).flatMap(recursiveListFiles)
  }
  def typedefFiles = recursiveListFiles(new File("../views"))
  val typedefStrings = typedefFiles.map(f =>
    Source.fromFile(f).mkString).map(s =>
    s.split("\\-\\-\\-").toSeq).flatMap(x =>
    x).map(_.trim).filter(_.length > 0) toSeq
  val typedefs = typedefStrings map loadTypeDef
  def loadTypeDef(typeDef: String) = {
    case class YamlXsdTypeDef(
      @BeanProperty var name: String,
      @BeanProperty var fields: ArrayList[YamlXsdTypeDef]) {
      def this() = this(null, null)
      def this(name: String) = this(name, null)
    }
    def xsdTypeDef(yamlTypeDef: YamlXsdTypeDef): XsdTypeDef = yamlTypeDef match {
      case YamlXsdTypeDef(name, null) => XsdTypeDef(name, null)
      case YamlXsdTypeDef(name, fields) =>
        XsdTypeDef(name, fields.map(f => xsdTypeDef(f)).toList)
    }
    xsdTypeDef((new Yaml).loadAs(typeDef, classOf[YamlXsdTypeDef]))
  }
  private def xsdName(name: String) = Schema.dbNameToXsdName(name)
  private def xsdNameToDbName(xsdName: String) = Schema.xsdNameToDbName(xsdName)
  private def annotation(comment: String) =
    if (comment != null && comment.trim.length > 0)
      <xs:annotation>
        <xs:documentation>{ comment }</xs:documentation>
      </xs:annotation>
  private def createElement(elName: String, col: XsdCol) = {
    val colcomment = annotation(col.comment)
    col.xsdType match {
      case XsdType(typeName, None, None, None) =>
        <xs:element name={ elName } type={ "xs:" + typeName } minOccurs="0">{
          colcomment
        }</xs:element>
      case XsdType(typeName, Some(length), None, None) =>
        <xs:element name={ elName } minOccurs="0">
          { colcomment }
          <xs:simpleType>
            <xs:restriction base={ "xs:" + typeName }>
              <xs:length value={ length.toString }/>
            </xs:restriction>
          </xs:simpleType>
        </xs:element>
      case XsdType(typeName, _, Some(totalDigits), fractionDigitsOption) =>
        <xs:element name={ elName } minOccurs="0">
          { colcomment }
          <xs:simpleType>
            <xs:restriction base={ "xs:" + typeName }>
              <xs:totalDigits value={ totalDigits.toString }/>
              {
                if (fractionDigitsOption != None)
                  <xs:fractionDigits value={ fractionDigitsOption.get.toString }/>
              }
            </xs:restriction>
          </xs:simpleType>
        </xs:element>
    }
  }
  private val md = Schema.entities.map(e => (e.name, e)).toMap
  def createComplexType(typeDef: XsdTypeDef) = {
    val tableMd = md(typeDef.name)
    val cols = tableMd.cols.map(c => (c.name, c)).toMap
    <xs:complexType name={ xsdName(typeDef.name) }>
      { annotation(tableMd.comment) }
      <xs:sequence>
        { // TODO nillable="true" minOccurs="0" maxOccurs="unbounded">
          // TODO when no restriction:  type="xs:string"
          typeDef.fields.map(f => {
            val dbFieldName = xsdNameToDbName(f.name) // TODO by db name?
            val col = cols(dbFieldName)
            createElement(xsdName(f.name), col)
          })
        }
      </xs:sequence>
    </xs:complexType>
  }
  def createSchema = {
    <xs:schema xmlns:tns="kps.ldz.lv" xmlns:xs="http://www.w3.org/2001/XMLSchema" version="1.0" targetNamespace="kps.ldz.lv">
      { typedefs map createComplexType }
    </xs:schema>
  }
  def createSchemaString = new PrettyPrinter(200, 2).format(createSchema)
}
