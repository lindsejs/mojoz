package mojoz.metadata

import scala.annotation.tailrec

import mojoz.metadata.in.I18nRules
import TableDef._
import ColumnDef._
import ViewDef._
import FieldDef._

case class Type(name: String, length: Option[Int],
  totalDigits: Option[Int], fractionDigits: Option[Int], isComplexType: Boolean) {
  def this(name: String) = this(name, None, None, None, false)
  def this(name: String, isComplexType: Boolean) =
    this(name, None, None, None, isComplexType)
  def this(name: String, length: Int) =
    this(name, Some(length), None, None, false)
  def this(name: String, totalDigits: Int, fractionDigits: Int) =
    this(name, None, Some(totalDigits), Some(fractionDigits), false)

  def intDigits = totalDigits.map(n => n - fractionDigits.getOrElse(0))
}
object TableDef {
  case class DbIndex(
    name: String,
    cols: Seq[String])
  case class Ref(
    name: String,
    cols: Seq[String],
    refTable: String,
    refCols: Seq[String],
    defaultTableAlias: String,
    defaultRefTableAlias: String,
    onDeleteAction: String,
    onUpdateAction: String)
  case class CheckConstraint(
    name: String,
    // TODO check constraint deferrability?
    // TODO table check constraint or column check constraint?
    expression: String)
  trait TableDefBase[+T] {
    val name: String
    val comments: String
    val cols: Seq[ColumnDefBase[T]]
    val pk: Option[DbIndex]
    val uk: Seq[DbIndex]
    val ck: Seq[CheckConstraint]
    val idx: Seq[DbIndex]
    val refs: Seq[Ref]
  }
}

case class TableDef[+T](
  name: String,
  comments: String,
  cols: Seq[ColumnDef[T]],
  pk: Option[DbIndex],
  uk: Seq[DbIndex],
  ck: Seq[CheckConstraint],
  idx: Seq[DbIndex],
  refs: Seq[Ref]) extends TableDefBase[T] {
  def toLowerCase = mapTableNames(_.toLowerCase)
    .mapColumnNames(_.toLowerCase)
    .mapConstraintNames(_.toLowerCase)
  def toSimpleNames = mapTableNames((s: String) =>
    if (s.indexOf(".") < 0) s else s.substring(s.lastIndexOf(".") + 1))
  def unprefixTableNames(prefix: String) = mapTableNames((s: String) =>
    if (s startsWith prefix) s.substring(prefix.length) else s)
  def mapTableNames(transform: (String) => String) = copy(
    name = transform(name),
    refs = refs.map(r => r.copy(refTable = transform(r.refTable))))
  def mapColumnNames(transform: (String) => String): TableDef[T] = copy(
    cols = cols.map(c => c.copy(name = transform(c.name))),
    pk = pk.map(x => x.copy(cols = x.cols.map(transform))),
    uk = uk.map(x => x.copy(cols = x.cols.map(transform))),
    idx = idx.map(x => x.copy(cols = x.cols.map(transform))),
    refs = refs.map(r => r.copy(
      cols = r.cols.map(transform),
      refCols = r.refCols.map(transform))))
  def mapConstraintNames(transform: (String) => String): TableDef[T] = {
    def safeTransform(s: String) = if (s == null) s else transform(s)
    def idxTransform(idx: TableDef.DbIndex) =
      idx.copy(name = safeTransform(idx.name))
    copy(
      pk = pk.map(idxTransform),
      uk = uk.map(idxTransform),
      idx = idx.map(idxTransform),
      refs = refs.map(r => r.copy(name = safeTransform(r.name))))
  }
}
object ColumnDef {
  trait ColumnDefBase[+T] {
    val name: String
    val type_ : T
    val nullable: Boolean
    val dbDefault: String
    val enum: Seq[String]
    val comments: String
  }
}
case class ColumnDef[+T](
  name: String,
  type_ : T,
  nullable: Boolean,
  dbDefault: String,
  enum: Seq[String],
  comments: String) extends ColumnDefBase[T]

class Metadata[T](
  val tableDefs: Seq[TableDefBase[T]],
  val viewDefs: Seq[ViewDefBase[FieldDefBase[T]]] = Nil,
  i18nRules: I18nRules = I18nRules.noI18n) {
  private lazy val md = tableDefs.map(e => (e.name, e)).toMap

  def tableDef(tableName: String) =
    md.get(tableName) getOrElse
      sys.error("table not found: " + tableName)

  def tableDefOption(typeDef: ViewDefBase[_]) =
    md.get(typeDef.table)

  def tableDef(typeDef: ViewDefBase[_]) =
    // TODO get line, file info from xsd type def
    md.get(typeDef.table) getOrElse
      sys.error("table not found: " + typeDef.table +
        ", type def: " + typeDef.name)

  def columnDef(viewDef: ViewDefBase[_], fieldDef: FieldDefBase[_]) = {
    val typeDef = viewDef
    val f = fieldDef
    val tableMd = tableDef(typeDef)
    val cols = tableMd.cols.map(c => (c.name, c)).toMap // TODO cache col map for all tables!
    val colName = DbConventions.xsdNameToDbName(f.name)
    try {
      (if (f.table == typeDef.table) cols
      else md(f.table).cols.map(c => (c.name, c)).toMap)(colName)
    } catch {
      case ex: Exception =>
        // TODO print filename, lineNr, colNr, too!
        throw new RuntimeException(
          "Problem finding column (typeDef: " + typeDef.name
            + ", column: " + f.table + "." + colName +
            (if (f.tableAlias == null) ""
            else " (table alias " + f.tableAlias + ")") + ")"
            + ", joins: " + typeDef.joins, ex)
    }
  }

  // typedef name to typedef
  val viewDef = viewDefs.map(t => (t.name, t)).toMap
  // typedef name to typedef with extended field list
  lazy val extendedViewDef = viewDefs.map(t =>
    if (t.extends_ == null) t else {
      @tailrec
      def baseFields(t: ViewDefBase[FieldDefBase[T]], fields: Seq[FieldDefBase[T]]): Seq[FieldDefBase[T]] =
        if (t.extends_ == null) t.fields ++ fields
        else baseFields(viewDef(t.extends_), t.fields ++ fields)
      t.copyWithFields(fields = baseFields(t, Nil))
    })
    .map(i18nRules.setI18n(this, _))
    .map(t => (t.name, t)).toMap
}
