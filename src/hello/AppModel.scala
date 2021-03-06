package hello

import mobireader.{Mobi, MobiContentParser}
import odt.OpenOfficeParser
import scalafx.collections.ObservableBuffer
import java.io._
import scalafx.Includes._
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import java.util.prefs.Preferences
import domain._
import scala.collection.mutable.ListBuffer
import db.DBHandler

import com.google.common.io.Files
import com.google.common.base.Charsets

/**
 * AppModel links all of the view/pages classes which should be
 * able to access same objects.
 * It also has lots of useful methods.
 */
class AppModel {
  val db = new DBHandler(getWorkspacePath + "/laughing.db")
  db.createTablesInDB()

  /**
   * Helper method which replaces content of a buffer not removing items
   * which are in current version and should be in future version.
   *
   * @param buffer
   * @param newContent
   * @tparam T
   */
  def replaceBufferContent[T](buffer: ObservableBuffer[T], newContent: List[T]) {
    buffer ++= newContent.toSet diff buffer.toSet
    buffer --= buffer.toSet diff newContent.toSet
  }

  def savePriorityBooksInDB() {
    val booksToSave = organizer.getAll()
    db.savePrioritizedBooks(booksToSave)
  }

  def loadPriorityBooksFromDB(): List[PrioritizedBook] = {
    db.getPrioritizedBooks()
  }

  def initialiseBookOrganizer(organizer: BookOrganizer) {
    for( book <- loadPriorityBooksFromDB()) {
      organizer.addBook(book)
      busyBooks += book.title
    }
  }

  def addDummyLibrary() {
    def createLibrary() = {
      val path = "/example/library/"
      val author1 = new Author("John Doe")
      val author2 = new Author("George Smith")
      val author3 = new Author("Anna White")

      val Physics = new Category("Physics")
      val Math = new Category("Math")
      val Biology = new Category("Biology")

      List(new Book("Dynamics", author1, path, "", Physics),
        new Book("Calculus for dummies", author2, path, "Easy", Math),
        new Book("Worms", author3, path, "Bleh", Biology),
        new Book("Algebra for dummies", author1, path, "Easy", Math)
      )
    }
    db addBooks createLibrary()
  }

  def normalizePath(path: String, maxSize: Int) = {
    val parts = path.split("/")
    if (path.size > maxSize) {
      parts.head + "/.../" + parts(parts.size - 2) + "/" + parts.last
    }
    else
      path
  }

  var libraryPath = new SimpleStringProperty(getLibraryPath)
  if (libraryPath.value == null)
    libraryPath = new SimpleStringProperty("./workspace/")

  var workspacePath = new SimpleStringProperty(getWorkspacePath)
  if (workspacePath.value == null)
    workspacePath = new SimpleStringProperty("./workspace/")

  val bookInfoUtility = new BookInfoUtility
  var mobi: Mobi = null

  var myBooks = db.getAllBooks()

  def isBookFormatSupported(path: String) = {
    val format = bookInfoUtility.extractFormatFromPath(path)
    bookInfoUtility.isFormatSupported(format)
  }

  def getBookTitleFromPath(path: String) = {
    bookInfoUtility.extractTitleFromPath(path)
  }

  var bookFormat = ""

  def updateBookFormatFromPath(path: String) {
    bookFormat = getBookFormatFromPath(path)
  }

  def getBookFormatFromPath(path: String) = {
    bookInfoUtility.extractFormatFromPath(path)
  }

  val books = getBooks
  val busyBooks = ListBuffer[String]()
  val organizer: BookOrganizer = new BookOrganizer()
  initialiseBookOrganizer(organizer)
  val freeBooks = getNamesOfFreeBooks

  def getNamesOfFreeBooks = {
    val names = new ObservableBuffer[String]()
    for(book <- books) {
      if(! (busyBooks contains book.getTitle )  )
        names += book.getTitle
    }
    names
  }

  /**
  * Updates authors in model. Check if any authors should be removed or added compared to DB
  *
  **/
  def updateNamesOfAuthors() {
    val authorsNamesFromDB = for (author <- db.getAllAuthors()) yield author.getName
    replaceBufferContent(observableList2ObservableBuffer(namesOfAuthors), authorsNamesFromDB)
  }

  def getBooks = {
    val books = new ObservableBuffer[Book]()
    for (book <- db.getAllBooks()) {
      books += book
    }
    books
  }

  def updateBooks() {
    val newBooks = getBooks
    replaceBufferContent(books, newBooks.toList)
    val newNames = getNamesOfFreeBooks
    replaceBufferContent(freeBooks, newNames.toList)
  }


  val listViewItems = new ObservableBuffer[String]()
  var categories = new CategoryTree
  categories.addSubcategories(List("Science", "Fiction", "Art"))
  categories("Fiction").addSubcategories(List("Science Fiction", "Soap operas", "Horror", "Fantasy"))
  categories("Science").addSubcategories(List("Computer science", "Biology"))

  var namesOfAllCategories: ObservableBuffer[String] = ObservableBuffer(for (category <-
                                                                             categories.allNames()) yield category)
  var nextToRead: ObservableBuffer[PrioritizedBook] =
    ObservableBuffer[PrioritizedBook](for (book <-
                                           organizer.getFirst(5)) yield book)

  val allToRead = ObservableBuffer[PrioritizedBook](organizer.getAll())

  def updateNextToRead() {
    val newList = organizer.getFirst(5)
    replaceBufferContent(nextToRead, newList)
    nextToRead.sort((lt, rt ) => lt.priority > rt.priority)
  }

  def updateAllToRead() {
    val newList = organizer.getAll
    replaceBufferContent(allToRead, newList)
    allToRead.sort((lt, rt ) => lt.priority > rt.priority)
  }

  def removeFromReadingBuffers(priorityBook: PrioritizedBook) {
    nextToRead -= priorityBook
    allToRead -= priorityBook
  }

  def addToReadingBuffers(priorityBook: PrioritizedBook) {
    nextToRead += priorityBook
    allToRead += priorityBook
  }

  def authors = db.getAllAuthors()

  var namesOfAuthors = FXCollections.observableArrayList((for (author <- authors) yield author.getName):_*)
  var filePath: String = ""
  var file: File = _
  var bookText = ""
  val bookTextPreview = new SimpleStringProperty("No preview available")

  def shortenBookText(bookText: String) = {
    val paragraphs = bookText.split("\n\n")
    val chosenParagraphs = (paragraphs slice (0, 20)) ++ (paragraphs slice (paragraphs.size - 50, paragraphs.size - 20))
    chosenParagraphs mkString "\n\n"
  }

  def updateBookText(path: String) {
    if (bookFormat == "mobi" || bookFormat == "bin") {
      val mobiParser = new Mobi(path)
      mobiParser.parse()
      val mobiContentParser = new MobiContentParser(mobiParser.readAllRecords())
      bookText = mobiContentParser.bodyWithParagraphs()
    }
    else if (bookFormat == "odt") {
      val odtParser = new OpenOfficeParser()
      bookText = odtParser.getText(path)
    }
    else if (bookFormat == "txt") {
      val book = new File(path)
      val fp = new RandomAccessFile(book, "r")
      val len = book.length.toInt
      val buff = new Array[Byte](len)
      fp.readFully(buff)
      bookText = new String(buff)
    }
  }

  def updatePreviewOfBookText() {
    val shortened = bookText.substring(0, (bookText.size * 0.05).toInt)
    val lastSpace = shortened.lastIndexOf(' ')
    if (lastSpace != -1)
      bookTextPreview.value = if (shortened(lastSpace - 1) == '.') {
        shortened.substring(0, lastSpace) + "..\n"
      }
      else shortened.substring(0, lastSpace) + "...\n"
  }

  def storeBookTextOnDisk(book: Book) {
    val category = book.category.category
    if(bookText.nonEmpty) {
      val pathToCategory = libraryPath.value + "/" + category
      val directoryOfCategory: File = new File(pathToCategory)
      if (!directoryOfCategory.exists()){
        directoryOfCategory.mkdirs()
      }
      val output = new File(book.getPathToContent)
      Files.write(bookText, output, Charsets.UTF_16)
    }
  }

  def fetchBookText(book: Book) =  {
    val in =  new File(book.getPathToContent)
    Files.toString(in, Charsets.UTF_16)
  }

  def getLibraryPath: String = {
    val prefs = Preferences.userNodeForPackage(classOf[AppModel])
    prefs.get("libraryPath", null)
  }

  def getWorkspacePath: String = {
    val prefs = Preferences.userNodeForPackage(classOf[AppModel])
    prefs.get("workspacePath", null)
  }

  def setLibraryPath(path: String) {
    val prefs = Preferences.userNodeForPackage(classOf[AppModel])
    if (path != null) {
      prefs.put("libraryPath", path)
    } else {
      prefs.remove("libraryPath")
    }
  }

  def setWorkspacePath(path: String) {
    val prefs = Preferences.userNodeForPackage(classOf[AppModel])
    if (path != null) {
      prefs.put("workspacePath", path)
    } else {
      prefs.remove("workspacePath")
    }
  }

  def updateWorkspacePath() {
    val workspacePathFromPreferences = getWorkspacePath
    if ( workspacePathFromPreferences != null)
      workspacePath.value = workspacePathFromPreferences
  }
}


	