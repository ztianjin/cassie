package com.twitter.cassie

import com.twitter.util.Future
import java.nio.ByteBuffer
import java.util.{ArrayList => JArrayList,HashMap => JHashMap,Iterator => JIterator,List => JList,Map => JMap,Set => JSet}
import org.apache.cassandra.finagle.thrift
import scala.collection.mutable.ListBuffer

class SuperCounterBatchMutationBuilder[Key, Name, SubName](cf: SuperCounterColumnFamily[Key, Name, SubName]) extends BatchMutation {

  case class Insert(key: Key, name: Name, column: CounterColumn[SubName])

  private val ops = new ListBuffer[Insert]

  def insert(key: Key, name: Name, column: CounterColumn[SubName]) = synchronized {
    ops.append(Insert(key, name, column))
    this
  }

  /**
   * Submits the batch of operations, returning a future to allow blocking for success.
   */
  def execute(): Future[Void] = {
    try {
      cf.batch(mutations)
    } catch {
      case e => Future.exception(e)
    }
  }

  private[cassie] def mutations: JMap[ByteBuffer, JMap[String, JList[thrift.Mutation]]] = synchronized {
    val mutations = new JHashMap[ByteBuffer, JMap[String, JList[thrift.Mutation]]]()

    ops.map { insert =>
      val cosc = new thrift.ColumnOrSuperColumn()
      val counterColumn = new thrift.CounterColumn(cf.subNameCodec.encode(insert.column.name), insert.column.value)
      val columns = new JArrayList[thrift.CounterColumn]()
      columns.add(counterColumn)
      val sc = new thrift.CounterSuperColumn(cf.nameCodec.encode(insert.name), columns)
      cosc.setCounter_super_column(sc)
      val mutation = new thrift.Mutation
      mutation.setColumn_or_supercolumn(cosc)

      val encodedKey = cf.keyCodec.encode(insert.key)

      val h = Option(mutations.get(encodedKey)).getOrElse { val x = new JHashMap[String, JList[thrift.Mutation]]; mutations.put(encodedKey, x); x }
      val l = Option(h.get(cf.name)).getOrElse { val y = new JArrayList[thrift.Mutation]; h.put(cf.name, y); y }
      l.add(mutation)
    }
    mutations
  }
}
