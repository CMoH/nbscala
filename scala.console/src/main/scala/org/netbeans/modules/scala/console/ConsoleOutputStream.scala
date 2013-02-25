package org.netbeans.modules.scala.console

import java.awt.Color
import java.awt.Point
import java.awt.Insets
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.util.concurrent.Future
import java.util.logging.Level
import java.util.logging.Logger
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.JViewport
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicComboPopup
import javax.swing.text.AttributeSet
import javax.swing.text.BadLocationException
import javax.swing.text.Document
import javax.swing.text.JTextComponent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import scala.collection.mutable.ArrayBuffer


trait ConsoleOutputLineParser {
  def parseLine(line: String): Array[(String, AttributeSet)]
}

class ConsoleCapturer {
  private val log = Logger.getLogger(getClass.getName)
  
  private var _isCapturing = false
  private var _captureText = new StringBuilder()
  private var _text: String = ""
  private var _inputLine: String = ""
  private var _postAction: ConsoleCapturer => Unit = capturer => ()
    
  def text = _text
  def inputLine = _inputLine

  def isCapturing = _isCapturing
  def discard {
    _isCapturing = false
  }
    
  def capture(postAction: ConsoleCapturer => Unit) {
    _isCapturing = true
    _text = ""
    _postAction = postAction
  }
    
  def endWith(inputLine: String) {
    _isCapturing = false
    _inputLine = inputLine
    _text = _captureText.toString
    _captureText.delete(0, _captureText.length)
      
    try {
      _postAction(this)
    } catch {
      case ex: Exception => log.log(Level.WARNING, ex.getMessage, ex)
    }
  }
    
  def append(str: String) {
    _captureText.append(str)
  }
}

/**
 *
 * @author Caoyuan Deng
 */
class ConsoleOutputStream(val area: JTextPane, pipedIn: PipedInputStream, welcome: String) extends OutputStream {
  private val log = Logger.getLogger(getClass.getName)
  
  def this(area: JTextPane) = this(area, null, null)
    
  /** buffer which will be used for the next line */
  private val buf = new StringBuffer(1000)
  private val linesBuf = new ArrayBuffer[String]()
  private var isWaitingUserInput = false
  private val outputCapturer = new ConsoleCapturer()
  
  lazy val defaultStyle = {
    val x = new SimpleAttributeSet()
    StyleConstants.setForeground(x, area.getForeground)     
    StyleConstants.setBackground(x, area.getBackground)
    x
  }

  lazy val sequenceStyle = {
    val x = new SimpleAttributeSet()
    StyleConstants.setForeground(x, area.getForeground)     
    StyleConstants.setBackground(x, area.getBackground)
    x
  }

  var currentStyle = defaultStyle

  /**
   * override to define custom line parser
   */
  protected val lineParser = new ConsoleOutputLineParser {
    def parseLine(line: String): Array[(String, AttributeSet)] = Array((line, defaultStyle))
  }

  val pipedOut = new PrintStream(new PipedOutputStream(pipedIn))
  private val doc = area.getDocument
  
  private object areaMouseListener extends ConsoleMouseListener(area)
  
  area.setCaret(BlockCaret)
  area.addKeyListener(terminalInput)
  area.addMouseListener(areaMouseListener)
  area.addMouseMotionListener(areaMouseListener)

  private lazy val completeCombo = {
    val x = new JComboBox[String]() {
      override def getInsets = new Insets(8, 8, 8, 8)
    }
    x.setFont(area.getFont)
    x.setRenderer(new DefaultListCellRenderer() )
    x
  }
  private lazy val completePopup = {
    val x = new BasicComboPopup(completeCombo)
    x.setFont(area.getFont)
    x
  }
        
  if (welcome ne null) {
    val messageStyle = new SimpleAttributeSet()
    StyleConstants.setBackground(messageStyle, area.getForeground)
    StyleConstants.setForeground(messageStyle, area.getBackground)
    overwrite(welcome, messageStyle)
  }
  
  private var _underlyingTask: Option[Future[Integer]] = None
  def underlyingTask = _underlyingTask
  def underlyingTask_=(underlyingTask: Option[Future[Integer]]) {
    _underlyingTask = underlyingTask
  }
  
  def runCommand(command: String): String = {
    pipedOut.println(command)
    ""
  }
  
  @throws(classOf[IOException])
  override
  def close() {
    flush
    handleClose
  }

  /**
   * This method is called when the stream is closed and it allows
   * extensions of this class to do additional tasks.
   * For example, closing an underlying stream, etc.
   * 
   * By override this method, extra tasks can be performance before/after the
   * existed task that have been defined here.
   */
  @throws(classOf[IOException])
  protected def handleClose() {
    underlyingTask map (_.cancel(true))
    
    area.removeKeyListener(terminalInput)
    area.removeMouseListener(areaMouseListener)
    area.removeMouseMotionListener(areaMouseListener)
  }

  @throws(classOf[IOException])
  override
  def write(b: Array[Byte]) {
    isWaitingUserInput = false
    buf.append(new String(b, 0, b.length))
  }

  @throws(classOf[IOException])
  override
  def write(b: Array[Byte], offset: Int, length: Int) {
    isWaitingUserInput = false
    buf.append(new String(b, offset, length))
  }

  @throws(classOf[IOException])
  override
  def write(b: Int) {
    isWaitingUserInput = false
    buf.append(b.toChar)
  }
  
  @throws(classOf[IOException])
  override
  def flush() {
    doFlush {currentLine =>
      isWaitingUserInput = currentLine.length > 0
      if (isWaitingUserInput && outputCapturer.isCapturing) {
        outputCapturer.endWith(currentLine)
      }
    }
  }
  
  /**
   * @param   an afterward action, with param 'current inputing line' which could be used or just ignore
   * @return  the last non-teminated line. if it's waiting for user input, this 
   *          line.length should > 0 (with at least one char)
   */
  protected[console] def doFlush(withAction: String => Unit = currentLine => ()): String = {
    val bufStr = buf.toString
    buf.delete(0, buf.length)
    val (lines, currentLine) = readLines(bufStr)

    if (outputCapturer.isCapturing) {
      lines foreach outputCapturer.append
    } 
    
    // doFlush may be called by input/output stream processing thread, whatever, we
    // will force Swing related code to be executed in event dispatch thread:
    SwingUtilities.invokeLater(new Runnable() {
        def run() {
          try {
            writeLines(lines)
            writeNonTeminatedLine(currentLine)
            withAction(currentLine)
          } catch {
            case ex: Exception => log.log(Level.SEVERE, ex.getMessage, ex)
          }
    
        }
      }
    )

    currentLine
  }
  
  /**
   * Read lines from buf, keep not-line-completed chars in buf
   * @return (lines, non-teminated line ie. current input line)
   */
  private def readLines(buf: String): (Array[String], String) = {
    val len = buf.length
    var newLineOffset = 0
    var readOffset = -1 // offset that has read to lines
    var i = 0
    while (i < len) {
      val c = buf.charAt(i)
      if (c == '\n' || c == '\r') {
        // trick: '\u0000' a char that is non-equalable with '\n' or '\r'
        val c1 = if (i + 1 < len) buf.charAt(i + 1) else '\u0000' 
        val line = buf.substring(newLineOffset, i) + "\n"  // strip '\r' for Windows
        if (c == '\n' && c1 == '\r' | c == '\r' && c1 == '\n') { 
          i += 1  // bypass '\r' for Windows
        }
        linesBuf += line
        readOffset = i
        newLineOffset = i + 1
      }
      
      i += 1
    }
    
    val rest = buf.substring(readOffset + 1, buf.length)
    
    val lines = linesBuf.toArray
    linesBuf.clear
    
    (lines, rest)
  }
    
  @throws(classOf[Exception])
  private def writeLines(lines: Array[String]) {
    lines foreach writeLine
  }

  /**
   * Write a line string to doc, to start a new line afterward, the line string should end with "\n"
   */
  @throws(classOf[Exception])
  private def writeLine(line: String) {
    lineParser.parseLine(line) foreach overwrite
  }
  
  /**
   * The last line is usaully the line that is accepting user input, ie. an editable 
   * line, we should process '\b' here (JTextPane just print ' ' for '\b')
   * @Note The '\b' from JLine, is actually a back cursor, which works with 
   * followed overwriting in combination for non-ansi terminal to move cursor.
   */
  @throws(classOf[Exception])
  private def writeNonTeminatedLine(str: String) {
    val len = str.length
    var i = 0
    while (i < len) {
      str.charAt(i) match {
        case '\b' =>
          backCursor(1)
        case c =>
          overwrite("" + c, currentStyle)
      }
      i += 1
    }
  }
  
  @throws(classOf[Exception])
  private def backCursor(num: Int) {
    val backNum = math.min(area.getCaretPosition, num)
    area.setCaretPosition(area.getCaretPosition - backNum)
  }
  
  @throws(classOf[Exception])
  private def overwrite(str_style: (String, AttributeSet)) {
    overwrite(str_style._1, str_style._2)
  }
  
  /**
   * @param string to overwrite
   * @param style
   */
  @throws(classOf[Exception])
  private def overwrite(str: String, style: AttributeSet) {
    val from = area.getCaretPosition
    val overwriteLen = math.min(doc.getLength - from, str.length)
    doc.remove(from, overwriteLen)
    doc.insertString(from, str, style)
    area.setCaretPosition(from + str.length)
  }
    

  protected def replaceText(start: Int, end: Int, replacement: String) {
    try {
      doc.remove(start, end - start)
      doc.insertString(start, replacement, currentStyle)
    } catch {
      case ex: BadLocationException => // Ifnore
    }
  }
    
  // --- complete actions
  
  protected def completePopupAction(capturer: ConsoleCapturer) {
    val text = capturer.text
    if (text.trim == "{invalid input}") {
      return
    }
    
    val candidates = text.split("\\s+") filter (_.length > 0)
    if (candidates.length == 0 || candidates.length == 1) {
      return
    }
    
    completePopup.getList.setVisibleRowCount(math.min(10, candidates.length))
    completeCombo.removeAllItems
    candidates foreach completeCombo.addItem
    
    val caretPos = area.getCaretPosition
    if (caretPos >= 0) {
      val pos = area.modelToView(caretPos)
      completePopup.show(area, pos.x, pos.y + area.getFontMetrics(area.getFont).getHeight)
    }
  }
  
  protected def completeUpSelectAction(evt: KeyEvent) {
    val selected = completeCombo.getSelectedIndex - 1
    if (selected >= 0) {
      completeCombo.setSelectedIndex(selected)
    } else {
      if (completeCombo.getItemCount > 0) {
        completeCombo.setSelectedIndex(completeCombo.getItemCount - 1)
      }
    }
  }
    
  protected def completeDownSelectAction(evt: KeyEvent) {
    val selected = completeCombo.getSelectedIndex + 1
    if (selected < completeCombo.getItemCount) {
      completeCombo.setSelectedIndex(selected)
    } else {
      if (completeCombo.getItemCount > 0) {
        completeCombo.setSelectedIndex(0)
      }
    }
  }
  
  protected def completeEnterSelectAction(evt: KeyEvent) {
    completeCombo.getSelectedItem match {
      case selectedText: String =>
        val input = outputCapturer.inputLine
        val len = math.min(input.length, selectedText.length)
        var matched = 0
        var i = 1
        while (i < len) {
          val toCompare = selectedText.substring(0, i)
          if (input.endsWith(toCompare)) {
            matched = i
          }
          i += 1
        }
        val complete = selectedText.substring(matched, selectedText.length)
        if (complete.length > 0) {
          terminalInput.write(complete.getBytes("utf-8"))
        }
      case _ =>
    }
  }

  object terminalInput extends TerminalInput with KeyListener {
    import KeyEvent._

    System.getProperty("os.name").toLowerCase match {
      case os if os.indexOf("windows") != -1 => terminalId = TerminalInput.JLineWindows
      case _ =>
    }

    override 
    def write(b: Array[Byte]) {
      pipedOut.write(b)
    }
    
    override 
    def keyPressed(evt: KeyEvent) {
      val keyCode = evt.getKeyCode
      
      // --- complete visiblilty
      if (completePopup.isVisible) {
        keyCode match {
          case VK_TAB =>
          case VK_UP =>    
            completeUpSelectAction(evt)
          case VK_DOWN =>  
            completeDownSelectAction(evt)
          case VK_ENTER => 
            // terminalInput process VK_ENTER in keyTyped, so keep completePopup.isVisible here
          case VK_ESCAPE => 
            // terminalInput will also process VK_ESCAPE in keyTyped later, so keep completePopup.isVisible here
          case _ =>
            if (!(evt.isControlDown || evt.isAltDown || evt.isMetaDown)) {
              completePopup.setVisible(false)
            }
            keyPressed(evt.getKeyCode, evt.getKeyChar, getModifiers(evt))
        }
      } else {
        keyCode match {
          case VK_TAB => 
            outputCapturer capture completePopupAction // do completion
          case _ => 
        }
        keyPressed(evt.getKeyCode, evt.getKeyChar, getModifiers(evt))
      }

      // --- evt consumes 
      keyCode match {
        case (VK_C | VK_A) if evt.isMetaDown | evt.isControlDown =>  // copy action
        case VK_V =>
          // we need a carefully implementation for paste action, since we should 
          // also move the cursor of backed jline to proper position, then send chars in clipboard.
          evt.consume
        case _ => evt.consume
      }
    }
  
    override 
    def keyReleased(evt: KeyEvent) {
      evt.consume
    }
    
    override 
    def keyTyped(evt: KeyEvent) {
      // under keyTyped, always use evt.getKeyChar
      val keyChar = evt.getKeyChar
      
      if (completePopup.isVisible) {
        keyChar match {
          case VK_ENTER => 
            completeEnterSelectAction(evt)
            completePopup.setVisible(false)
          case VK_ESCAPE =>
            completePopup.setVisible(false)
          case _ => 
            // XXX TODO incremental completion when letters typed
            //keyTyped(evt.getKeyCode, evt.getKeyChar, getModifiers(evt))
        }
      } else {
        keyTyped(evt.getKeyCode, evt.getKeyChar, getModifiers(evt))
      }
      
      evt.consume // consume it, will be handled by echo etc
    }
    
    private def getModifiers(e: KeyEvent): Int = {
      (if (e.isControlDown) TerminalInput.KEY_CONTROL else 0) |
      (if (e.isShiftDown) TerminalInput.KEY_SHIFT else 0) |
      (if (e.isAltDown) TerminalInput.KEY_ALT else 0) |
      (if (e.isActionKey) TerminalInput.KEY_ACTION else 0)
    }
  }
}

class AnsiConsoleOutputStream(term: ConsoleOutputStream) extends AnsiOutputStream(term) {
  import AnsiConsoleOutputStream._
  
  private val area = term.area
  private val doc = area.getDocument.asInstanceOf[StyledDocument]
  
  override
  protected def processSetForegroundColor(color: Int) {
    StyleConstants.setForeground(term.sequenceStyle, ANSI_COLOR_MAP(color))
    term.currentStyle = term.sequenceStyle
  }

  override
  protected def processSetBackgroundColor(color: Int) {
    StyleConstants.setBackground(term.sequenceStyle, ANSI_COLOR_MAP(color))
    term.currentStyle = term.sequenceStyle
  }
  
  override
  protected def processDefaultTextColor {
    StyleConstants.setForeground(term.sequenceStyle, term.defaultStyle.getAttribute(StyleConstants.Foreground).asInstanceOf[Color])
    term.currentStyle = term.sequenceStyle
  }

  override
  protected def processDefaultBackgroundColor {
    StyleConstants.setBackground(term.sequenceStyle, term.defaultStyle.getAttribute(StyleConstants.Background).asInstanceOf[Color])
    term.currentStyle = term.sequenceStyle
  }

  override
  protected def processSetAttribute(attribute: Int) {
    import Ansi._
    
    attribute match {
      case ATTRIBUTE_CONCEAL_ON =>
        //write("\u001B[8m")
        //concealOn = true
      case ATTRIBUTE_INTENSITY_BOLD =>
        StyleConstants.setBold(term.sequenceStyle, true)
      case ATTRIBUTE_INTENSITY_NORMAL =>
        StyleConstants.setBold(term.sequenceStyle, false)
      case ATTRIBUTE_UNDERLINE =>
        StyleConstants.setUnderline(term.sequenceStyle, true)
      case ATTRIBUTE_UNDERLINE_OFF =>
        StyleConstants.setUnderline(term.sequenceStyle, false)
      case ATTRIBUTE_NEGATIVE_ON =>
      case ATTRIBUTE_NEGATIVE_Off =>
      case _ =>
    }
    
    term.currentStyle = term.sequenceStyle
  }
	
  override
  protected def processAttributeRest() {
    term.currentStyle = term.defaultStyle
  }
  
  // @Note before do any ansi cursor command, we should flush first to keep the 
  // proper caret position which is sensitive to the order of ansi command and chars to print
  @throws(classOf[BadLocationException])
  override 
  protected def processCursorToColumn(col: Int) {
    term doFlush {currentLine => 
      val lineStart = getLineStartOffsetForPos(doc, area.getCaretPosition)
      val toPos = lineStart + col - 1
      area.setCaretPosition(toPos)
    }
  }
  
  /**
   * Clears part of the screen. If n is zero (or missing), clear from cursor to 
   * end of screen. If n is one, clear from cursor to beginning of the screen. 
   * If n is two, clear entire screen (and moves cursor to upper left on MS-DOS ANSI.SYS).
   */
  @throws(classOf[BadLocationException])
  override 
  protected def processEraseScreen(eraseOption: Int) {
    eraseOption match {
      case 0 => 
        term doFlush {currentLine =>
          val currPos = area.getCaretPosition
          doc.remove(currPos, doc.getLength - currPos)
        }
      case _ =>
    }
  }
  
  @throws(classOf[BadLocationException])
  override 
  protected def processEraseLine(eraseOption: Int) {
    eraseOption match {
      case 0 => 
        term doFlush {currentLine =>
          val currPos = area.getCaretPosition
          doc.remove(currPos, doc.getLength - currPos)
        }
      case _ =>
    }
  }

}

object AnsiConsoleOutputStream {
  private val log = Logger.getLogger(getClass.getName)
  
  private val ANSI_COLOR_MAP = Array(
    Color.BLACK, Color.RED, Color.GREEN, Color.YELLOW, Color.BLUE, Color.MAGENTA, Color.CYAN, Color.WHITE
  )
  
  // --- Document utilities
  
  /** 
   * indent the lines between the given document positions (NOT LINE NUMBERS !)
   */
  def indentLines(doc: Document, fromPos: Int, toPos: Int) {
    val lineStart = getLineNumber(doc, fromPos)
    val lineEnd = getLineNumber(doc, toPos)

    var line = lineStart
    while (line <= lineEnd) {
      try {
        val li = doc.getDefaultRootElement.getElement(line)
        doc.insertString(li.getStartOffset, " ", li.getAttributes)
      } catch {
        case ex: Exception => log.warning(ex.getMessage)
      }
      line += 1
    }
  }

  /** 
   * Indents the lines between the given document positions (positions, NOT LINE NUMBERS !)
   */
  def unindentLines(doc: Document, fromPos: Int, toPos: Int) {
    val lineStart = getLineNumber( doc, fromPos)
    val lineEnd = getLineNumber( doc, toPos )

    var line = lineStart
    while (line <= lineEnd) {
      try {
        val li = doc.getDefaultRootElement().getElement(line)
        val ci = doc.getText(li.getStartOffset, 1).charAt(0)
        if(Character.isWhitespace(ci) && ci !='\n') {
          doc.remove(li.getStartOffset, 1)
        }
      } catch {
        case ex: Exception => log.warning(ex.getMessage)
      }
      line += 1
    }
  }

  /** 
   * comment out the lines between the given document positions (NOT LINE NUMBERS !)
   * comment out means adding "//" at the beginning of the line.
   * the uncommentout is the reverse operation
   */
  def commentOutLines(doc: Document, fromPos: Int, toPos: Int) {
    val lineStart = getLineNumber( doc, fromPos)
    val lineEnd = getLineNumber( doc, toPos )

    var line = lineStart
    while (line <= lineEnd) {
      try {
        val li = doc.getDefaultRootElement.getElement(line);
        doc.insertString(li.getStartOffset, "//", li.getAttributes)
      } catch {
        case ex: Exception => log.warning(ex.getMessage)
      }
      line += 1
    }
  }

  /** 
   * uncomment out the lines between the given document positions (NOT LINE NUMBERS !)
   * reverse operation of commentOut. This only removes // at the beginning
   */
  def unCommentOutLines(doc: Document, fromPos: Int, toPos: Int) {
    val lineStart = getLineNumber( doc, fromPos)
    val lineEnd = getLineNumber( doc, toPos )

    var line = lineStart
    while (line <= lineEnd) {
      try {
        val li = doc.getDefaultRootElement.getElement(line)
        if(li.getEndOffset()-li.getStartOffset>1) {
          if(doc.getText(li.getStartOffset, 2).equals("//"))
          {
            doc.remove(li.getStartOffset, 2)
          }
        }
      } catch {
        case ex: Exception => log.warning(ex.getMessage)
      }
      line += 1
    }
  }

  /**
   * @return the line number corresponding to the document position.
   * the first line has index 0.
   */
  def getLineNumber(doc: Document, pos: Int): Int = {
    // a document is modelled as a list of lines (Element)=> index = line number
    val rrot = doc.getDefaultRootElement
    rrot.getElementIndex(pos)
  }

  /* not used alone... always together with the line. so we minimize mismatch with offset !
   * @return the column, first is 0
   * use getLineColumnNumbers() to directly receive line and column numbers !
   *
   public static int getColumnNumber_(Document doc, int pos)
   {
   Element map = doc.getDefaultRootElement();
   int line = map.getElementIndex(pos);
   Element lineElt = map.getElement(line);
   return pos-lineElt.getStartOffset();
   }*/

  /** 
   * @return the {line,column}, first is 0
   */
  def getLineColumnNumbers(doc: Document, pos: Int): Array[Int] = {
    val root = doc.getDefaultRootElement
    val line = root.getElementIndex(pos)
    val lineElt = root.getElement(line)
    Array(line, pos - lineElt.getStartOffset)
  }

  def getLineStartOffsetForPos(doc: Document, pos: Int): Int = {
    // a document is modelled as a list of lines (Element)=> index = line number
    doc match {
      case sDoc: StyledDocument =>
        val line = sDoc.getParagraphElement(pos)
        line.getStartOffset
      case _ =>
        val root = doc.getDefaultRootElement
        val line = root.getElementIndex(pos)
        val lineElt = root.getElement(line)
        lineElt.getStartOffset
    }
  }

  @throws(classOf[Exception])
  def deleteLineAtPos(doc: StyledDocument, pos: Int) {
    val line = doc.getParagraphElement(pos)
    doc.remove(line.getStartOffset, line.getEndOffset - line.getStartOffset)
  }

  /** 
   * @return the text at the line containing the given position. With eventual carriage return and line feeds...
   */
  def getTextOfLineAtPosition(doc: StyledDocument, pos: Int): String = {
    // a document is modelled as a list of lines (Element)=> index = line number
    val line = doc.getParagraphElement(pos);
    try {
      doc.getText(line.getStartOffset(), line.getEndOffset()-line.getStartOffset());
    } catch {
      case ex: Exception => null
    }
  }

  /** @return the text at the line containing the given position
   */
  def getTextOfLineAtPosition_onlyUpToPos(doc: StyledDocument, pos: Int): String = {
    // a document is modelled as a list of lines (Element)=> index = line number
    val line = doc.getParagraphElement(pos)
    try {
      doc.getText(line.getStartOffset, pos-line.getStartOffset)
    } catch {
      case ex: Exception => null
    }
  }

  /** @return the text at the line containing the given position
   */
  def getTextOfLine(doc: Document, line: Int): String = {
    // a document is modelled as a list of lines (Element)=> index = line number
    val map = doc.getDefaultRootElement
    val lineElt = map.getElement(line)
    try {
      doc.getText(lineElt.getStartOffset, lineElt.getEndOffset-lineElt.getStartOffset)
    } catch {
      case ex: Exception => null
    }
  }

  def getSpacesAtBeginning(text: String): String = {
    val sb = new StringBuilder()
    var break = false
    var i = 0
    while (i < text.length && !break) {
      val ci = text.charAt(i)
      if (Character.isWhitespace(ci) && ci!='\r' && ci!='\n') {
        sb.append(text.charAt(i))
      } else {
        break = true
      }
      i += 1
    }
    
    sb.toString
  }


  def scrollPosToMiddle(pos: Int, textPane: JTextPane, editorScrollPane: JScrollPane, h: Int) {
    try
    {
      val r = textPane.modelToView(pos)
      //r.translate(0,-this.getHeight()/4);
      //textPane.scrollRectToVisible( r );
      //this.scroll
      //int h2 = (int)editorScrollPane.getViewport().getExtentSize().getHeight()/2;
      //System.out.println(""+h2);
      editorScrollPane.getViewport().setViewPosition(new Point(0, Math.max(r.y - h, 0)) )
      //textPane.requestFocus(); // to make the selection visible

    } catch {
      case ex: Exception =>
    }
  }


  /** in fact, 1/4 below upper limit is nicer...
   scrolls the start of the line to the middle of the screen
   */
  def scrollToMiddle(tp: JTextComponent, pos: Int) {
    // look if a parent exists, this is only the case IF the text pane has been added in a scrollpane
    if (tp.getParent == null) return
    tp.getParent match {
      case null => return
      case vp: JViewport =>
        try {
          val pt = tp.modelToView(pos)
          var h = (pt.getY - vp.getHeight / 4).toInt
          if (h < 0) h = 0
          vp.setViewPosition(new Point(0, h))
        } catch {
          case ex: Exception => log.warning(ex.getMessage)
        }
      case _ =>
        new Throwable("parent of textpane is not a viewport !").printStackTrace
    }
  }

  def getVisibleDocPosBounds(textPane: JTextPane, editorScrollPane: JScrollPane): Array[Int] = {
    val pos = Array.ofDim[Int](2)
    try {
      val pt1 = editorScrollPane.getViewport.getViewPosition
      pos(0) = textPane.viewToModel(pt1)
      val dim = editorScrollPane.getViewport.getExtentSize

      val pt2 = new Point((pt1.getX + dim.getWidth).toInt, (pt1.getY + dim.getHeight).toInt)
      pos(1) = textPane.viewToModel(pt2)
    } catch {
      case ex: Exception => log.warning(ex.getMessage)
    }
    pos
  }

  /** @param line zero based.
   * @param column zero based
   */
  def getDocPositionFor(doc: Document, line: Int, column: Int): Int = {
    if (line < 0) return -1
    val map = doc.getDefaultRootElement
    val lineElt = map.getElement(line)
    try {
      val pos = lineElt.getStartOffset + (if (column > 0) column else 0)
      if (pos < 0) return 0
      if (pos > doc.getLength) return doc.getLength
      pos
    } catch {
      case ex: Exception => throw new RuntimeException(ex)}
  }
}