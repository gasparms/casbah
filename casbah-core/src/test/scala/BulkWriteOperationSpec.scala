/**
 * Copyright (c) 2010 MongoDB, Inc. <http://mongodb.com>
 * Copyright (c) 2009, 2010 Novus Partners, Inc. <http://novus.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * For questions and comments about this product, please see the project page at:
 *
 * http://github.com/mongodb/casbah
 *
 */

package com.mongodb.casbah.test.core

import scala.collection.mutable

import java.lang.UnsupportedOperationException

import com.mongodb.BulkWriteUpsert

import com.mongodb.casbah.Imports._


@SuppressWarnings(Array("deprecation"))
class BulkWriteOperationSpec extends CasbahDBTestSpecification {

  def initializeBulkOperation(ordered: Boolean): BulkWriteOperation = {
    ordered match {
      case true => collection.initializeOrderedBulkOperation
      case false => collection.initializeUnorderedBulkOperation
    }
  }

  Seq(true, false) foreach {
    ordered =>
      val builderName = {
        ordered match {
          case true => "OrderedBulkOperation"
          case false => "UnorderedBulkOperation"
        }
      }

      "The " + builderName should {
        "when no document with the same id exists, should insert the document " in {
          collection.drop()

          val operation =  initializeBulkOperation(ordered)
          operation.insert(MongoDBObject("_id" -> 1))

          val result = operation.execute()

          result.insertedCount must beEqualTo(1)
          result.upserts.size must beEqualTo(0)
          collection.findOne() must beSome(MongoDBObject("_id" -> 1))
        }

        "when a document contains a key with an illegal character, inserting it should throw IllegalArgumentException" in {
          collection.drop()

          val operation =  initializeBulkOperation(ordered)
          operation.insert(MongoDBObject("$set" -> 1))
          operation.execute() should throwA[IllegalArgumentException]
        }

        "when a document with the same id exists, should throw an exception" in {
          collection.drop()

          val document = MongoDBObject("_id" -> 1)
          collection.insert(document)

          val operation =  initializeBulkOperation(ordered)
          operation.insert(document)

          operation.execute() should throwA[BulkWriteException]
        }

        "when a document with no _id  is inserted, the _id should be generated by the driver" in {
          collection.drop()

          val document = MongoDBObject()

          val operation =  initializeBulkOperation(ordered)
          operation.insert(document)
          operation.execute()

          collection.findOne() must haveSomeField("_id")
      }

        "when documents match the query, a remove of one should remove one of them" in {
          collection.drop()
          collection += MongoDBObject("x" -> true)
          collection += MongoDBObject("x" -> true)

          val operation =  initializeBulkOperation(ordered)
          operation.find(MongoDBObject("x" -> true)).removeOne()

          val result = operation.execute()
          result.removedCount must beEqualTo(1)
          result.upserts.size must beEqualTo(0)
          collection.count() must beEqualTo(1)
        }

        "when documents match the query, a remove should remove all of them" in {
          collection.drop()
          collection += MongoDBObject("x" -> true)
          collection += MongoDBObject("x" -> true)
          collection += MongoDBObject("x" -> false)

          val operation =  initializeBulkOperation(ordered)
          operation.find(MongoDBObject("x" -> true)).remove()

          val result = operation.execute()
          result.removedCount must beEqualTo(2)
          result.upserts.size must beEqualTo(0)
          collection.count() must beEqualTo(1)
        }

        "when an update document contains a non $-prefixed key, update should throw IllegalArgumentException" in {
          collection.drop()
          val operation =  initializeBulkOperation(ordered)
          operation.find(MongoDBObject()).update($set("x" -> 1) ++ ("y" -> 2))
          operation.execute() should throwA[IllegalArgumentException]
        }

        "when an update document contains a non $-prefixed key, updateOne should throw IllegalArgumentException" in {
          val operation =  initializeBulkOperation(ordered)
          operation.find(MongoDBObject()).update($set("x" -> 1) ++ ("y" -> 2))
          operation.execute() should throwA[IllegalArgumentException]
        }

        "when multiple document match the query, updateOne should update only one of them" in {
          collection.drop()
          collection += MongoDBObject("x" -> true)
          collection += MongoDBObject("x" -> true)

          val operation =  initializeBulkOperation(ordered)
          operation.find(MongoDBObject("x" -> true)).updateOne($set("y" -> 1))

          val result = operation.execute()
          result.matchedCount must beEqualTo(1)
          serverIsAtLeastVersion(2, 5) match {
            case true => result.modifiedCount must beSuccessfulTry.withValue(1)
            case false => result.modifiedCount must beFailedTry.withThrowable[UnsupportedOperationException]
          }
          collection.find(MongoDBObject("y" -> 1)).count() must beEqualTo(1)
        }

        "when multiple document match the query, update should update all of them" in {
          collection.drop()
          collection += MongoDBObject("x" -> true)
          collection += MongoDBObject("x" -> true)

          val operation =  initializeBulkOperation(ordered)
          operation.find(MongoDBObject("x" -> true)).update($set("y" -> 1))

          val result = operation.execute()
          result.matchedCount must beEqualTo(2)
          serverIsAtLeastVersion(2, 5) match {
            case true => result.modifiedCount must beSuccessfulTry.withValue(2)
            case false => result.modifiedCount must beFailedTry.withThrowable[UnsupportedOperationException]
          }
          collection.find(MongoDBObject("y" -> 1)).count() must beEqualTo(2)
        }

        "when no document matches the query, update with upsert should insert a document" in {
          collection.drop()
          val id = new ObjectId()
          val operation = initializeBulkOperation(ordered)
          operation.find(MongoDBObject("_id" -> id)).upsert().update($set("x" -> 2))

          val result = operation.execute()
          result.getUpserts.size should beEqualTo(1)
          result.upserts must beEqualTo(mutable.Buffer(new BulkWriteUpsert(0, id)))
          collection.findOne() should beSome(MongoDBObject("_id" -> id, "x" -> 2))
        }

        "when no document matches the query, update with upsert should insert a document with custom _id" in {
          serverIsAtLeastVersion(2, 5) must beTrue.orSkip("Needs server >= 2.5")
          collection.drop()

          val query = MongoDBObject("_id" -> 101)
          val operation =  initializeBulkOperation(ordered)
          operation.find(query).upsert().updateOne($set("x" -> 2))

          val result = operation.execute()
          result.upserts.size must beEqualTo(1)
          result.upserts must beEqualTo(mutable.Buffer(new BulkWriteUpsert(0, 101)))
          collection.findOne() must beSome(MongoDBObject("_id" -> 101, "x" -> 2))
        }

        "when documents matches the query, update with upsert should update all of them" in {
          collection.drop()
          collection.insert(MongoDBObject("x" -> true))
          collection.insert(MongoDBObject("x" -> true))
          collection.insert(MongoDBObject("x" -> false))

          val operation = initializeBulkOperation(ordered)
          operation.find(MongoDBObject("x" -> true)).upsert().update($set("y" -> 1))

          val result = operation.execute()
          result.matchedCount should beEqualTo(2)
          serverIsAtLeastVersion(2, 5) match {
            case true => result.modifiedCount must beSuccessfulTry.withValue(2)
            case false => result.modifiedCount must beFailedTry.withThrowable[UnsupportedOperationException]
          }
          collection.count(MongoDBObject("y" -> 1)) should beEqualTo(2)

        }

        "when a document contains a key with an illegal character, replacing a document with it should throw IllegalArgumentException" in {
          val operation = initializeBulkOperation(ordered)
          val query = MongoDBObject("_id" -> new ObjectId())

          operation.find(query).upsert().replaceOne($set("x" -> 1))
          operation.execute() should throwA[IllegalArgumentException]
        }

        "when no document matches the query, a replace with upsert should insert a document" in {
          collection.drop()
          collection += MongoDBObject("_id" -> 101)

          val operation =  initializeBulkOperation(ordered)
          operation.find(MongoDBObject("_id" -> 101)).upsert().replaceOne(MongoDBObject("_id" -> 101, "x" -> 2))

          val result = operation.execute()
          result.matchedCount must beEqualTo(1)
          result.upserts.size must beEqualTo(0)
          collection.count() must beEqualTo(1)
          collection.findOne() must beSome(MongoDBObject("_id" -> 101, "x" -> 2))
        }

        "when multiple documents match the query, replaceOne should replace one of them" in {
          collection.drop()
          collection.insert(MongoDBObject("x" -> true))
          collection.insert(MongoDBObject("x" -> true))

          val operation = initializeBulkOperation(ordered)
          val replacement = MongoDBObject("y" -> 1, "x" -> false)
          operation.find(MongoDBObject("x" -> true)).replaceOne(replacement)
          operation.execute()

          collection.findOne(MongoDBObject("x" -> false), MongoDBObject("_id" -> 0)) should beSome(replacement)
        }

        "when a document matches the query, updateOne with upsert should update that document" in {
          collection.drop()
          val id = new ObjectId()
          collection.insert(MongoDBObject("_id" -> id))
          val operation = initializeBulkOperation(ordered)

          operation.find(MongoDBObject("_id" -> id)).upsert().updateOne($set("x" -> 2))
          operation.execute()

          collection.findOne() should beSome(MongoDBObject("_id" -> id, "x"-> 2))
        }

        "when a document matches the query, a replace with upsert should update that document" in {
          collection.drop()
          collection.insert(MongoDBObject("_id" -> 1))

          val operation = initializeBulkOperation(ordered)
          operation.find(MongoDBObject("_id" -> 1)).upsert().replaceOne(MongoDBObject("_id" -> 1, "x" -> 2))
          operation.execute()

          collection.findOne() should beSome(MongoDBObject("_id" -> 1, "x" -> 2))
        }
      }

      "handle multi-length runs of unacknowledged insert, update, replace, and remove" in {
        collection.drop()
        collection.insert(testInserts: _*)

        collection.getDB.requestStart()

        val operation = initializeBulkOperation(ordered)
        addWritesTo(operation)

        val result = operation.execute(WriteConcern.Unacknowledged)
        collection.insert(MongoDBObject("_id" -> 9))

        result.isAcknowledged must beFalse
        collection.findOne(MongoDBObject("_id" -> 1)) must beSome(MongoDBObject("_id" -> 1, "x" -> 2))
        collection.findOne(MongoDBObject("_id" -> 2)) must beSome(MongoDBObject("_id" -> 2, "x" -> 3))
        collection.findOne(MongoDBObject("_id" -> 3)) must beNone
        collection.findOne(MongoDBObject("_id" -> 4)) must beNone
        collection.findOne(MongoDBObject("_id" -> 5)) must beSome(MongoDBObject("_id" -> 5, "x" -> 4))
        collection.findOne(MongoDBObject("_id" -> 6)) must beSome(MongoDBObject("_id" -> 6, "x" -> 5))
        collection.findOne(MongoDBObject("_id" -> 7)) must beSome(MongoDBObject("_id" -> 7))
        collection.findOne(MongoDBObject("_id" -> 8)) must beSome(MongoDBObject("_id" -> 8))

        collection.getDB.requestDone()
        success
      }
  }

    "BulkWriteOperations" should {

    "handle multi-length runs of ordered insert, update, replace, and remove" in {
      collection.drop()
      collection.insert(testInserts: _*)

      val operation =  collection.initializeOrderedBulkOperation
      addWritesTo(operation)
      operation.execute()

      collection.findOne(MongoDBObject("_id" -> 1)) must beSome(MongoDBObject("_id" -> 1, "x" -> 2))
      collection.findOne(MongoDBObject("_id" -> 2)) must beSome(MongoDBObject("_id" -> 2, "x" -> 3))
      collection.findOne(MongoDBObject("_id" -> 3)) must beNone
      collection.findOne(MongoDBObject("_id" -> 4)) must beNone
      collection.findOne(MongoDBObject("_id" -> 5)) must beSome(MongoDBObject("_id" -> 5, "x" -> 4))
      collection.findOne(MongoDBObject("_id" -> 6)) must beSome(MongoDBObject("_id" -> 6, "x" -> 5))
      collection.findOne(MongoDBObject("_id" -> 7)) must beSome(MongoDBObject("_id" -> 7))
      collection.findOne(MongoDBObject("_id" -> 8)) must beSome(MongoDBObject("_id" -> 8))
    }


    "error details should have correct index on ordered write failure" in {
      collection.drop()

      val operation =  collection.initializeOrderedBulkOperation
      operation.insert(MongoDBObject("_id" -> 1))
      operation.find(MongoDBObject("_id" -> 1)).updateOne($set("x" -> 3))
      operation.insert(MongoDBObject("_id" -> 1))

      try {
        operation.execute()
      } catch {
        case ex: BulkWriteException =>
          ex.writeErrors.size must beEqualTo(1)
          ex.writeErrors(0).getIndex must beEqualTo(2)
          ex.writeErrors(0).getCode must beEqualTo(11000)
          ex.writeResult must beAnInstanceOf[BulkWriteResult]
      }
      true
    }

    "handle multi-length runs of unordered insert, update, replace, and remove" in {

      collection.drop()
      collection.insert(testInserts: _*)

      val operation =  collection.initializeUnorderedBulkOperation
      addWritesTo(operation)

      val result = operation.execute()
      result.insertedCount must beEqualTo(2)

      result.matchedCount must beEqualTo(4)
      result.removedCount must beEqualTo(2)
      result.upserts.size must beEqualTo(0)
      serverIsAtLeastVersion(2, 5) match {
        case true => result.modifiedCount must beSuccessfulTry.withValue(4)
        case false => result.modifiedCount must beFailedTry.withThrowable[UnsupportedOperationException]
      }

      collection.findOne(MongoDBObject("_id" -> 1)) must beSome(MongoDBObject("_id" -> 1, "x" -> 2))
      collection.findOne(MongoDBObject("_id" -> 2)) must beSome(MongoDBObject("_id" -> 2, "x" -> 3))
      collection.findOne(MongoDBObject("_id" -> 3)) must beNone
      collection.findOne(MongoDBObject("_id" -> 4)) must beNone
      collection.findOne(MongoDBObject("_id" -> 5)) must beSome(MongoDBObject("_id" -> 5, "x" -> 4))
      collection.findOne(MongoDBObject("_id" -> 6)) must beSome(MongoDBObject("_id" -> 6, "x" -> 5))
      collection.findOne(MongoDBObject("_id" -> 7)) must beSome(MongoDBObject("_id" -> 7))
      collection.findOne(MongoDBObject("_id" -> 8)) must beSome(MongoDBObject("_id" -> 8))
    }

    "error details should have correct index on unordered write failure" in {
      collection.drop()
      collection.insert(testInserts: _*)

      val operation =  collection.initializeUnorderedBulkOperation
      operation.insert(MongoDBObject("_id" -> 1))
      operation.find(MongoDBObject("_id" -> 2)).updateOne(MongoDBObject("$set" -> MongoDBObject("x" -> 3)))
      operation.insert(MongoDBObject("_id" -> 3))

      try {
        operation.execute()
      } catch {
        case ex: BulkWriteException =>
          ex.writeErrors.size must beEqualTo(2)
          ex.writeErrors(0).getIndex must beEqualTo(0)
          ex.writeErrors(0).getCode must beEqualTo(11000)
          ex.writeErrors(1).getIndex must beEqualTo(2)
          ex.writeErrors(1).getCode must beEqualTo(11000)
          ex.writeResult must beAnInstanceOf[BulkWriteResult]
          ex.getMessage must startWith("Bulk write operation error")
      }
      success
    }

    "test write concern exceptions" in {
      val mongoClient = MongoClient(List(new ServerAddress()))
      isReplicaSet must beTrue.orSkip("Testing writeConcern on ReplicaSet")
      try {
        val operation: BulkWriteOperation = collection.initializeUnorderedBulkOperation
        operation.insert(MongoDBObject())
        operation.execute(new WriteConcern(5, 1, false, false))
        failure("Execute should have failed")
      } catch {
        case e: BulkWriteException => (e.getWriteConcernError must not).beNull
        case _: Throwable => failure("Unexpected exception")
      }
      success
    }
  }

  def testInserts = {
    List(MongoDBObject("_id" -> 1),
         MongoDBObject("_id" -> 2),
         MongoDBObject("_id" -> 3),
         MongoDBObject("_id" -> 4),
         MongoDBObject("_id" -> 5),
         MongoDBObject("_id" -> 6)
    )
  }

  def addWritesTo(operation: BulkWriteOperation) {
    operation.find(MongoDBObject("_id" -> 1)).updateOne($set("x" -> 2))
    operation.find(MongoDBObject("_id" -> 2)).updateOne($set("x" -> 3))
    operation.find(MongoDBObject("_id" -> 3)).removeOne()
    operation.find(MongoDBObject("_id" -> 4)).removeOne()
    operation.find(MongoDBObject("_id" -> 5)).replaceOne(MongoDBObject("_id" -> 5, "x" -> 4))
    operation.find(MongoDBObject("_id" -> 6)).replaceOne(MongoDBObject("_id" -> 6, "x" -> 5))
    operation.insert(MongoDBObject("_id" -> 7))
    operation.insert(MongoDBObject("_id" -> 8))
  }
}
