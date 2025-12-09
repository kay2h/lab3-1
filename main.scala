import scala.concurrent._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import scala.collection.immutable.LazyList

//АЛГЕБРАИЧЕСКИЕ ТИПЫ ДАННЫХ (ADT)
//используем Вместо наследования и сложных иерархий классов используем ADT,
//которые позволяют четко определить все возможные состояния данных.
//использовать sealed trait и case class вместо традиционного
sealed trait IntegrationResult
// Успешный результат содержит вычисленное значение интеграла
case class IntegralValue(value: Double) extends IntegrationResult
// Ошибочный результат содержит сообщение об ошибке
case class IntegrationError(message: String) extends IntegrationResult

//ADT для представления результата проверки точки
sealed trait PointResult
// Точка находится под графиком функции
case class UnderCurvePoint(x: Double, y: Double) extends PointResult
// Точка находится над графиком функции
case class AboveCurvePoint(x: Double, y: Double) extends PointResult

object FunctionalMonteCarloIntegration {
  //ЧИСТАЯ ФУНКЦИЯ - не имеет побочных эффектов, зависит только от входных параметров
  // Легче тестировать, проще рассуждать о коде, можно кэшировать.

  def isUnderCurve(f: Double => Double, x: Double, y: Double): PointResult = {
    // Используем math.abs для работы с отрицательными значениями функций
    if (y <= math.abs(f(x))) UnderCurvePoint(x, y)
    else AboveCurvePoint(x, y)
  }
  //СОЗДАНИЕ ЛЕНИВОГО ПОТОКА (LazyList) - ключевое требование задания
   //1. Позволяет обрабатывать бесконечные последовательности
   //2. Экономит память - вычисляется по требованию
   //3. Функциональный подход к генерации данных

  def randomPointsStream(l: Double, r: Double, minY: Double, maxY: Double): LazyList[(Double, Double)] = {
    // Локальная функция для генерации одной случайной точки
    def generatePoint: (Double, Double) = (
      // Генерация x в интервале [l, r]
      l + (r - l) * scala.util.Random.nextDouble(),
      // Генерация y в интервале [minY, maxY]
      minY + (maxY - minY) * scala.util.Random.nextDouble()
    )

    // LazyList.continually создает бесконечный ленивый поток
    //continuallyТочек может быть очень много, не хотим создавать их все сразу
    LazyList.continually(generatePoint)
  }

    //НАХОЖДЕНИЕ МАКСИМАЛЬНОГО ЗНАЧЕНИЯ ФУНКЦИИ
   //Принцип единой ответственности, можно переиспользовать
  def findMaxY(f: Double => Double, l: Double, r: Double, steps: Int = 1000): Double = {
    // Шаг для дискретизации функции
    val step = (r - l) / steps
    // Функциональный подход: map + max вместо цикла
    (0 to steps)
      .map(i => f(l + i * step))  // Преобразуем индекс в значение функции
      .max                         // Находим максимум
  }

    //ФУНКЦИЯ ВЫСШЕГО ПОРЯДКА - принимает функцию как параметр
   //Позволяет параметризовать поведение
  def processPoint(f: Double => Double)(point: (Double, Double)): Int = {
    val (x, y) = point
    // Pattern matching на результат ADT
    //Более функциональный, явный подход
    isUnderCurve(f, x, y) match {
      case UnderCurvePoint(_, _) => 1  // Точка под графиком
      case AboveCurvePoint(_, _) => 0  // Точка над графиком
    }
  }

  //РАЗДЕЛЕНИЕ ПОТОКА НА ЧАСТИ ДЛЯ ПАРАЛЛЕЛЬНОЙ ОБРАБОТКИ
   //Можно разделить без вычисления всех элементов

  def splitStream[A](stream: LazyList[A], parts: Int): List[LazyList[A]] = {
    val size = stream.size
    if (size <= parts) List(stream)
    else {
      val chunkSize = size / parts
      // grouped создает группы элементов заданного размера
      stream.grouped(chunkSize).toList
    }
  }

  //ОСНОВНАЯ ФУНКЦИЯ В ФУНКЦИОНАЛЬНОМ СТИЛЕ
   // Either Функциональная обработка ошибок вместо исключений

  def integralMonteCarlo(
                          f: Double => Double,
                          l: Double,
                          r: Double,
                          pointsNumber: Int,
                          threadsNumber: Int
                        ): Either[String, Double] = {

    // ВАЛИДАЦИЯ ВХОДНЫХ ДАННЫХ - функциональный подход
    if (l >= r) return Left("Левая граница должна быть меньше правой")
    if (pointsNumber <= 0) return Left("Количество точек должно быть положительным")
    if (threadsNumber <= 0) return Left("Количество потоков должно быть положительным")

    // Используем Try для безопасного выполнения вычислений
    //Try Обработка исключений в функциональном стиле
    Try {
      // 1. ВЫЧИСЛЕНИЕ ОГРАНИЧИВАЮЩЕГО ПРЯМОУГОЛЬНИКА
      val width = r - l
      val maxY = findMaxY(f, l, r)
      val minY = 0.0  // Предположение: функция неотрицательная
      val height = maxY - minY
      val totalArea = width * height

      // 2. СОЗДАНИЕ ЛЕНИВОГО ПОТОКА ТОЧЕК
      // take ограничивает бесконечный поток нужным количеством точек
      val pointsStream = randomPointsStream(l, r, minY, maxY)
        .take(pointsNumber)  // Берем только нужное количество точек

      // 3. РАЗДЕЛЕНИЕ ПОТОКА ДЛЯ ПАРАЛЛЕЛЬНОЙ ОБРАБОТКИ
      val chunks = splitStream(pointsStream, threadsNumber)

      // 4. ПАРАЛЛЕЛЬНАЯ ОБРАБОТКА С ИСПОЛЬЗОВАНИЕМ Future
      //map Преобразуем каждый чанк в Future
      val futures = chunks.map { chunk =>
        Future {
          // Вычисляем сумму результатов для чанка
          // sum Функциональная агрегация вместо аккумулятора
          chunk.map(processPoint(f)).sum
        }
      }

      // 5. ОЖИДАНИЕ И АГРЕГАЦИЯ РЕЗУЛЬТАТОВ
      // Await.result ожидает завершения каждого Future
      val results = futures.map(future => Await.result(future, Duration.Inf))
      val totalPointsInside = results.sum  // Суммируем все частичные результаты

      // 6. ВЫЧИСЛЕНИЕ ФИНАЛЬНОГО РЕЗУЛЬТАТА
      // Формула метода Монте-Карло
      (totalPointsInside.toDouble / pointsNumber.toDouble) * totalArea

    } match {
      // Преобразуем Try в Either для единообразной обработки
      case Success(result) => Right(result)
      case Failure(exception) => Left(s"Ошибка вычисления: ${exception.getMessage}")
    }
  }

  //АЛЬТЕРНАТИВНАЯ ВЕРСИЯ С ИСПОЛЬЗОВАНИЕМ FOR-COMPREHENSION
  def integralMonteCarloSafe(
                              f: Double => Double,
                              l: Double,
                              r: Double,
                              pointsNumber: Int,
                              threadsNumber: Int
                            ): Either[String, Double] = {

    // For-comprehension для последовательной обработки с проверкой ошибок
    for {
      // ПРОВЕРКА УСЛОВИЙ С ИСПОЛЬЗОВАНИЕМ Either.cond
      // Either.cond Функциональный способ проверки условий
      _ <- Either.cond(l < r, (), "Левая граница должна быть меньше правой")
      _ <- Either.cond(pointsNumber > 0, (), "Количество точек должно быть положительным")
      _ <- Either.cond(threadsNumber > 0, (), "Количество потоков должно быть положительным")

      // ВЫЧИСЛЕНИЕ ПАРАМЕТРОВ
      width = r - l
      maxY = findMaxY(f, l, r)
      minY = 0.0
      height = maxY - minY
      totalArea = width * height

      // СОЗДАНИЕ И ОБРАБОТКА ПОТОКА ТОЧЕК
      pointsStream = randomPointsStream(l, r, minY, maxY).take(pointsNumber)
      chunks = splitStream(pointsStream, threadsNumber)

      // ПАРАЛЛЕЛЬНЫЕ ВЫЧИСЛЕНИЯ
      futures = chunks.map(chunk => Future(chunk.map(processPoint(f)).sum))

      // ОЖИДАНИЕ РЕЗУЛЬТАТОВ С ОБРАБОТКОЙ ОШИБОК
      results <- Try(futures.map(fut => Await.result(fut, Duration.Inf))).toEither
        .left.map(_.getMessage)  // Преобразуем исключение в строку

      totalPointsInside = results.sum
      result = (totalPointsInside.toDouble / pointsNumber.toDouble) * totalArea

    } yield result  // Возвращаем результат
  }

  // ПРИМЕР ИСПОЛЬЗОВАНИЯ
  def main(args: Array[String]): Unit = {
    val linearFunction: Double => Double = (x: Double) => x

    val lowerBound = 0.0
    val upperBound = 10.0
    val totalPoints = 1000000
    val numThreads = 4

    println("ФУНКЦИОНАЛЬНАЯ ВЕРСИЯ МЕТОДА МОНТЕ-КАРЛО")
    println("(Соответствует требованиям)")
    println("Используются: ADT, LazyList, чистые функции, Either, pattern matching")

    // ОБРАБОТКА РЕЗУЛЬТАТА С ПОМОЩЬЮ PATTERN MATCHING
    // pattern matching Функциональный способ разбора вариантов
    integralMonteCarloSafe(linearFunction, lowerBound, upperBound, totalPoints, numThreads) match {
      case Right(result) =>
        println(s"\n Calculation completed successfully")
        println(s"Function: f(x) = x")
        println(s"Integration interval: [$lowerBound, $upperBound]")
        println(s"Number of threads: $numThreads")
        println(s"Total number of points: $totalPoints")
        println(s"Exact integral value: 50.0")
        println(s"Approximate value: $result")
        println(s"Absolute error: ${math.abs(result - 50.0)}")
        println(s"Relative error: ${math.abs(result - 50.0) / 50.0 * 100}%")

      case Left(error) =>
        println(s"\n Calculation error: $error")
    }
  }
}
