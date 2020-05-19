package caliban.execution

import java.util.UUID

import caliban.CalibanError.ExecutionError
import caliban.GraphQL._
import caliban.Macros.gqldoc
import caliban.RootResolver
import caliban.TestUtils._
import caliban.Value.{ BooleanValue, StringValue, EnumValue }
import caliban.InputValue.{ ListValue, ObjectValue }
import caliban.parsing.adt.LocationInfo
import zio.IO
import zio.stream.ZStream
import zio.test.Assertion._
import zio.test.environment.TestEnvironment
import zio.test._

object ExecutionSpec extends DefaultRunnableSpec {

  override def spec: ZSpec[TestEnvironment, Any] =
    suite("ExecutionSpec")(
      testM("skip directive") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
            query test {
              amos: character(name: "Amos Burton") {
                name
                nicknames @skip(if: true)
              }
            }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"amos":{"name":"Amos Burton"}}""")
        )
      },
      testM("simple query with fields") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
            {
              characters {
                name
              }
            }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo(
            """{"characters":[{"name":"James Holden"},{"name":"Naomi Nagata"},{"name":"Amos Burton"},{"name":"Alex Kamal"},{"name":"Chrisjen Avasarala"},{"name":"Josephus Miller"},{"name":"Roberta Draper"}]}"""
          )
        )
      },
      testM("arguments") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
            {
              characters(origin: MARS) {
                name
                nicknames
              }
            }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo(
            """{"characters":[{"name":"Alex Kamal","nicknames":[]},{"name":"Roberta Draper","nicknames":["Bobbie","Gunny"]}]}"""
          )
        )
      },
      testM("arguments with list coercion") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
            {
              charactersIn(names: "Alex Kamal") {
                name
              }
            }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"charactersIn":[{"name":"Alex Kamal"}]}""")
        )
      },
      testM("aliases") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             {
               amos: character(name: "Amos Burton") {
                 name
                 nicknames
               },
               naomi: character(name: "Naomi Nagata") {
                 name
                 nicknames
               },
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"amos":{"name":"Amos Burton","nicknames":[]},"naomi":{"name":"Naomi Nagata","nicknames":[]}}""")
        )
      },
      testM("fragment") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             {
               amos: character(name: "Amos Burton") {
                 ...info
               }
             }

             fragment info on Character {
               name
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"amos":{"name":"Amos Burton"}}""")
        )
      },
      testM("inline fragment") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             {
               amos: character(name: "Amos Burton") {
                 name
                 role {
                   ... on Mechanic {
                     shipName
                   }
                 }
               }
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"amos":{"name":"Amos Burton","role":{"shipName":"Rocinante"}}}""")
        )
      },
      testM("effectful query") {
        val interpreter = graphQL(resolverIO).interpreter
        val query       = gqldoc("""
               {
                 characters {
                   name
                 }
               }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo(
            """{"characters":[{"name":"James Holden"},{"name":"Naomi Nagata"},{"name":"Amos Burton"},{"name":"Alex Kamal"},{"name":"Chrisjen Avasarala"},{"name":"Josephus Miller"},{"name":"Roberta Draper"}]}"""
          )
        )
      },
      testM("mutation") {
        val interpreter = graphQL(resolverWithMutation).interpreter
        val query       = gqldoc("""
               mutation {
                 deleteCharacter(name: "Amos Burton")
               }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"deleteCharacter":{}}"""))
      },
      testM("variable") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             query test($name: String!){
               amos: character(name: $name) {
                 name
               }
             }""")

        assertM(
          interpreter.flatMap(_.execute(query, None, Map("name" -> StringValue("Amos Burton")))).map(_.data.toString)
        )(equalTo("""{"amos":{"name":"Amos Burton"}}"""))
      },
      testM("variable in list") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             query test($name: String) {
               amos: charactersIn(names: [$name]){
                 name
               }
              }""")

        assertM(
          interpreter.flatMap(_.execute(query, None, Map("name" -> StringValue("Amos Burton")))).map(_.data.toString)
        )(equalTo("""{"amos":[{"name":"Amos Burton"}]}"""))
      },
      testM("variable in object") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             query test($name: String) {
               exists(character: { name: $name, nicknames: [], origin: EARTH })
              }""")

        assertM(
          interpreter.flatMap(_.execute(query, None, Map("name" -> StringValue("Amos Burton")))).map(_.data.toString)
        )(equalTo("""{"exists":false}"""))
      },
      testM("input object given as variable") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             query test($character: CharacterObjectArgs!) {
               exists(character: $character)
              }""")

        val characterArg = ObjectValue(Map(
            "name" -> StringValue("Amos Burton"),
            "nicknames" -> ListValue(List()),
            "origin" -> EnumValue("EARTH")
          ))

        val variables = Map("character" -> characterArg)

        assertM(
          interpreter.flatMap(_.execute(query, None, variables)).map(_.data.toString)
        )(equalTo("""{"exists":false}"""))
      },
      testM("error message for invalid input object") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             query test($character: CharacterObjectArgs!) {
               exists(character: $character)
              }""")

        val characterArg = ObjectValue(Map(
            "name" -> StringValue("Amos Burton"),
            "nicknames" -> ListValue(List())
          ))

        val variables = Map("character" -> characterArg)

        assertM(
          interpreter.flatMap(_.execute(query, None, variables)).map(_.errors.toString)
        )(equalTo("""TODO: Better Error Message Here"""))
      },
      testM("skip directive") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             query test{
               amos: character(name: "Amos Burton") {
                 name
                 nicknames @skip(if: true)
               }
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"amos":{"name":"Amos Burton"}}""")
        )
      },
      testM("include directive") {
        val interpreter = graphQL(resolver).interpreter
        val query       = gqldoc("""
             query test($included: Boolean!){
               amos: character(name: "Amos Burton") {
                 name
                 nicknames @include(if: $included)
               }
             }""")

        assertM(
          interpreter.flatMap(_.execute(query, None, Map("included" -> BooleanValue(false)))).map(_.data.toString)
        )(equalTo("""{"amos":{"name":"Amos Burton"}}"""))
      },
      testM("test Map") {
        case class Test(map: Map[Int, String])
        val interpreter = graphQL(RootResolver(Test(Map(3 -> "ok")))).interpreter
        val query       = gqldoc("""
             {
               map {
                 key
                 value
               }
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"map":[{"key":3,"value":"ok"}]}""")
        )
      },
      testM("test Either") {
        case class Test(either: Either[Int, String])
        val interpreter = graphQL(RootResolver(Test(Right("ok")))).interpreter
        val query       = gqldoc("""
             {
               either {
                 left
                 right
               }
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"either":{"left":null,"right":"ok"}}""")
        )
      },
      testM("test UUID") {
        case class IdArgs(id: UUID)
        case class Queries(test: IdArgs => UUID)
        val interpreter = graphQL(RootResolver(Queries(_.id))).interpreter
        val query       = gqldoc("""
             {
               test(id: "be722453-d97d-48c2-b535-9badd1b5d4c9")
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(
          equalTo("""{"test":"be722453-d97d-48c2-b535-9badd1b5d4c9"}""")
        )
      },
      testM("mapError") {
        import io.circe.syntax._
        case class Test(either: Either[Int, String])
        val api   = graphQL(RootResolver(Test(Right("ok"))))
        val query = """query{}"""

        for {
          interpreter <- api.interpreter
          result      <- interpreter.mapError(_ => "my custom error").execute(query)
        } yield assert(result.errors)(equalTo(List("my custom error"))) &&
          assert(result.asJson.noSpaces)(equalTo("""{"data":null,"errors":[{"message":"my custom error"}]}"""))
      },
      testM("merge 2 APIs") {
        case class Test(name: String)
        case class Test2(id: Int)
        val api1        = graphQL(RootResolver(Test("name")))
        val api2        = graphQL(RootResolver(Test2(2)))
        val interpreter = (api1 |+| api2).interpreter
        val query =
          """query{
            |  name
            |  id
            |}""".stripMargin
        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"name":"name","id":2}"""))
      },
      testM("error path") {
        case class A(b: B)
        case class B(c: IO[Throwable, Int])
        case class Test(a: A)
        val e           = new Exception("boom")
        val interpreter = graphQL(RootResolver(Test(A(B(IO.fail(e)))))).interpreter
        val query       = gqldoc("""
              {
                a {
                  b {
                    c
                  }
                }
              }""")
        assertM(interpreter.flatMap(_.execute(query)).map(_.errors))(
          equalTo(
            List(
              ExecutionError(
                "Effect failure",
                List(Left("a"), Left("b"), Left("c")),
                Some(LocationInfo(21, 5)),
                Some(e)
              )
            )
          )
        )
      },
      testM("ZStream used in a query") {
        case class Queries(test: ZStream[Any, Throwable, Int])
        val interpreter = graphQL(RootResolver(Queries(ZStream(1, 2, 3)))).interpreter
        val query       = gqldoc("""
             {
               test
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"test":[1,2,3]}"""))
      },
      testM("ZStream used in a subscription") {
        case class Queries(test: Int)
        case class Subscriptions(test: ZStream[Any, Throwable, Int])
        val interpreter =
          graphQL(RootResolver(Queries(1), Option.empty[Unit], Subscriptions(ZStream(1, 2, 3)))).interpreter
        val query = gqldoc("""
             subscription {
               test
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"test":<stream>}"""))
      },
      testM("Circe Json scalar") {
        import io.circe.Json
        import caliban.interop.circe.json._
        case class Queries(test: Json)

        val interpreter = graphQL(RootResolver(Queries(Json.obj(("a", Json.fromInt(333)))))).interpreter
        val query       = gqldoc("""
             {
               test
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"test":{"a":333}}"""))
      },
      testM("Play Json scalar") {
        import play.api.libs.json._
        import caliban.interop.play.json._
        case class Queries(test: JsValue)

        val interpreter = graphQL(RootResolver(Queries(Json.obj(("a", JsNumber(333)))))).interpreter
        val query       = gqldoc("""
             {
               test
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"test":{"a":333}}"""))
      },
      testM("test Interface") {
        case class Test(i: Interface)
        val interpreter = graphQL(RootResolver(Test(Interface.B("ok")))).interpreter
        val query       = gqldoc("""
             {
               i {
                 id
               }
             }""")

        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"i":{"id":"ok"}}"""))
      },
      testM("argument not wrapped in a case class") {
        case class Query(test: Int => Int)
        val api         = graphQL(RootResolver(Query(identity)))
        val interpreter = api.interpreter
        val query =
          """query{
            |  test(value: 1)
            |}""".stripMargin
        assertM(interpreter.flatMap(_.execute(query)).map(_.data.toString))(equalTo("""{"test":1}"""))
      }
    )
}
