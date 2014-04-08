package mojoz.metadata.in

import java.lang.Integer
import java.sql.Connection
import java.sql.{ DatabaseMetaData => DM }
import java.sql.ResultSet
import java.sql.{ ResultSet => RS }
import java.sql.Types

import scala.collection.mutable.ListBuffer

import mojoz.metadata.ColumnDef
import mojoz.metadata.DbIndex
import mojoz.metadata.Ref
import mojoz.metadata.TableDef
import mojoz.metadata.{ XsdType => Type }

object JdbcMdLoader {
  def loadTableDefs(conn: Connection,
    catalog: String, schemaPattern: String, tableNamePattern: String,
    types: String*) = {
    val tableDefs = ListBuffer[TableDef]()
    val dmd = conn.getMetaData
    val rs = dmd.getTables(catalog, schemaPattern, tableNamePattern,
      if (types.size == 0) null else types.toArray)
    while (rs.next) {
      val catalog = rs.getString("TABLE_CAT")
      val schema = rs.getString("TABLE_SCHEM")
      val tableName = rs.getString("TABLE_NAME")
      val comment = rs.getString("REMARKS")
      val cols = loadColDefs(dmd.getColumns(catalog, schema, tableName, null))
      val pk = loadPk(dmd.getPrimaryKeys(catalog, schema, tableName))
      val uk = Nil // TODO uk
      val idx = Nil // TODO idx
      val refs = loadRefs(dmd.getImportedKeys(catalog, schema, tableName))
      val tableFullName =
        List(catalog, schema, tableName).filter(_ != null).mkString(".")
      tableDefs += TableDef(tableFullName, comment, cols, pk, uk, idx, refs)
    }

    // work around oracle bugs
    if (conn.getClass.getName.startsWith("oracle")) {
      if (!tableDefs.exists(_.comment != null)) {
        val st = conn.prepareStatement(
          "select comments from all_tab_comments" +
            " where owner || '.' || table_name = ?",
          RS.TYPE_FORWARD_ONLY, RS.CONCUR_READ_ONLY, RS.CLOSE_CURSORS_AT_COMMIT)
        tableDefs transform { td =>
          st.setString(1, td.name)
          val rs = st.executeQuery()
          val comment = if (rs.next) rs.getString(1) else null
          rs.close()
          st.clearParameters()
          if (comment == null) td else td.copy(comment = comment)
        }
      }
      if (!tableDefs.exists(_.cols.exists(_.comment != null))) {
        val st = conn.prepareStatement(
          "select column_name, comments from all_col_comments" +
            " where owner || '.' || table_name = ?",
          RS.TYPE_FORWARD_ONLY, RS.CONCUR_READ_ONLY, RS.CLOSE_CURSORS_AT_COMMIT)
        tableDefs transform { td =>
          st.setString(1, td.name)
          val rs = st.executeQuery()
          var cList: List[(String, String)] = Nil
          while (rs.next)
            cList = (rs.getString(1), rs.getString(2)) :: cList
          rs.close()
          st.clearParameters()
          val cMap = cList.toMap
          if (!cMap.values.exists(c => c != null && c != "")) td
          else td.copy(cols = td.cols.map(c =>
            c.copy(comment = cMap.get(c.name).orNull)))
        }
      }
    }
    tableDefs.toList
  }
  def checkToEnum(check: String) =
    // TODO properly, regexp at least
    Option(check).map(_ split "[\\s'\\(\\),]+")
      .map(_.toList.filter(_ != ""))
      .map {
        case "check" :: _ :: "in" :: tail => tail
        case x => Nil
      }.filter(_.size > 0).orNull
  def loadColDefs(rs: ResultSet) = {
    val cols = ListBuffer[ColumnDef]()
    while (rs.next) {
      val name = rs.getString("COLUMN_NAME")
      val jdbcTypeCode = rs.getInt("DATA_TYPE") // TODO use it?
      val dbTypeName = rs.getString("TYPE_NAME")
      val size = rs.getInt("COLUMN_SIZE")
      val fractionDigits = rs.getInt("DECIMAL_DIGITS")
      val nullable = rs.getInt("NULLABLE") == DM.columnNullable
      val dbDefault = rs.getString("COLUMN_DEF")
      val comment = rs.getString("REMARKS")
      val check = null // TODO check constraint for column (enum)!
      // TODO extract mapping, use db type!
      val dbType = JdbcToXsdTypeMapper.map(jdbcTypeCode, size, fractionDigits)
      cols += ColumnDef(name, dbType, nullable, dbDefault, check, comment)
    }
    rs.close
    cols.toList
  }
  def loadPk(rs: ResultSet) = {
    var cols: List[(Short, String, String)] = Nil
    while (rs.next) {
      val keySeq = rs.getShort("KEY_SEQ")
      val colName = rs.getString("COLUMN_NAME")
      val pkName = rs.getString("PK_NAME")
      cols = (keySeq, colName, pkName) :: cols
    }
    rs.close
    val pkName = cols.map(_._3).headOption.orNull
    if (cols.size == 0) None else Some(DbIndex(pkName, cols.sorted.map(_._2)))
  }
  def loadRefs(rs: ResultSet) = {
    def fkRule(rule: Short) = rule match {
      case DM.importedKeyNoAction | DM.importedKeyRestrict => "no action"
      case DM.importedKeyCascade => "cascade"
      case DM.importedKeySetNull => "set null"
      case DM.importedKeySetDefault => "set default"
      case _ => sys.error("Unexpected fk rule: " + rule)
    }
    val refMap = scala.collection.mutable.Map[(String, String), Ref]()
    while (rs.next) {
      val pkTabCat = rs.getString("PKTABLE_CAT")
      val pkTabSch = rs.getString("PKTABLE_SCHEM")
      val pkTabName = rs.getString("PKTABLE_NAME")
      val pkColName = rs.getString("PKCOLUMN_NAME")
      val fkColName = rs.getString("FKCOLUMN_NAME")
      val keySeq = rs.getShort("KEY_SEQ")
      val updateRule = fkRule(rs.getShort("UPDATE_RULE"))
      val deleteRule = fkRule(rs.getShort("DELETE_RULE"))
      val fkName = rs.getString("FK_NAME")
      val deferrability = rs.getShort("DEFERRABILITY") match {
        case DM.importedKeyInitiallyDeferred => "deferred"
        case DM.importedKeyInitiallyImmediate => "immediate"
        case DM.importedKeyNotDeferrable => "not deferrable"
      }
      val pkTabFullName =
        List(pkTabCat, pkTabSch, pkTabName).filter(_ != null).mkString(".")
      refMap.get(pkTabFullName, fkName) match {
        case Some(r) =>
          r.cols.asInstanceOf[ListBuffer[String]] += fkColName
          r.refCols.asInstanceOf[ListBuffer[String]] += pkColName
        case None =>
          // TODO deferrability to ref!
          refMap += (pkTabFullName, fkName) ->
            Ref(fkName, ListBuffer(fkColName), pkTabFullName,
              ListBuffer(pkColName), null, null, deleteRule, updateRule)
      }
    }
    rs.close
    refMap.values
      .map(r => r.copy(cols = r.cols.toList, refCols = r.refCols.toList))
      .toList
  }
}

// java.sun.com/j2se/1.5.0/docs/guide/jdbc/getstart/GettingStartedTOC.fm.html
object JdbcToXsdTypeMapper {
  def integerOrSubtype(len: Int) =
    if (len > 18) new Type("integer", None, Some(len.toInt), None, false)
    else if (len > 9) new Type("long", None, Some(len.toInt), None, false)
    else new Type("int", None, Some(len.toInt), None, false)
  def map(jdbcTypeCode: Int, size: Integer, fractionDigits: Integer) =
    jdbcTypeCode match {
      case Types.ARRAY => new Type("base64Binary")
      case Types.BIGINT => new Type("long")
      case Types.BINARY => new Type("base64Binary")
      case Types.BIT => new Type("boolean")
      case Types.BLOB => new Type("base64Binary")
      case Types.BOOLEAN => new Type("boolean")
      case Types.CHAR => new Type("string", size)
      case Types.CLOB => new Type("string", size)
      case Types.DATALINK => new Type("string", size) // anyURI instead?
      case Types.DATE => new Type("date")
      case Types.DECIMAL | Types.NUMERIC =>
        // TODO choose best base type? use integerOrSubtype(len: Int)?
        new Type("decimal", size, fractionDigits)
      case Types.DISTINCT =>
        // TODO need base type to solve this correctly
        new Type("string")
      case Types.DOUBLE => new Type("double")
      case Types.FLOAT => new Type("double") // Yes, double, not float! I mean it.
      case Types.INTEGER => new Type("int") // Yes, int, not integer! I mean it.
      case Types.JAVA_OBJECT => new Type("base64Binary")
      case Types.LONGNVARCHAR => new Type("string")
      case Types.LONGVARBINARY => new Type("base64Binary")
      case Types.LONGVARCHAR => new Type("string", size)
      case Types.NCHAR => new Type("string", size)
      case Types.NCLOB => new Type("string", size)
      case Types.NULL => new Type("string")
      case Types.NVARCHAR => new Type("string", size)
      case Types.OTHER => new Type("base64Binary")
      case Types.REAL => new Type("float")
      case Types.REF => new Type("string", size)
      case Types.ROWID => new Type("string", size)
      case Types.SMALLINT => new Type("short")
      case Types.SQLXML => new Type("string")
      case Types.STRUCT => new Type("base64Binary")
      case Types.TIME => new Type("time")
      case Types.TIMESTAMP => new Type("dateTime")
      case Types.TINYINT =>
        // TODO? signed/unsigned byte?
        new Type("short")
      case Types.VARBINARY => new Type("base64Binary")
      case Types.VARCHAR => new Type("string", size)
      case x => sys.error("Unexpected jdbc type code: " + x)
    }
}