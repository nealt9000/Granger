package app.granger.ebook

/**
 * Keeps a selection to the page on screen, run before a menu action reads it. Dragging a handle into a paragraph that
 * began on an earlier page makes the WebView flip the selection's start back there on each move (logged on the Pixel 6,
 * 2026-11-05; a column layout's paragraph split across columns, in the browser's own handle logic, which no page CSS
 * changed). The highlight flashes, and without this the action would take text from the page before. A selection set from
 * script loses its handles and menu, so this runs only as the action closes them anyway, not during the drag.
 */
internal object OnPageSelection {
    const val JS = """
(function(){
  var s=getSelection();
  if(!s.rangeCount||s.isCollapsed)return 'none';
  var r=s.getRangeAt(0);
  function onPage(n,o){var c=document.createRange();c.setStart(n,o);c.collapse(true);var q=c.getClientRects();return !q.length||(q[0].left>=-2&&q[0].left<=innerWidth+2);}
  var startOk=onPage(r.startContainer,r.startOffset),endOk=onPage(r.endContainer,r.endOffset);
  if(startOk&&endOk)return 'whole';
  var pad=parseFloat(getComputedStyle(document.body).paddingLeft)||0;
  function caret(x,from,to,step){for(var y=from;y!==to;y+=step){var c=document.caretRangeFromPoint(x,y);if(c&&onPage(c.startContainer,c.startOffset))return c;}return null;}
  var a=startOk?r:caret(pad+1,0,innerHeight,2),b=endOk?r:caret(innerWidth-pad-1,innerHeight-1,-1,-2);
  if(!a||!b)return 'none';
  s.setBaseAndExtent(a.startContainer,a.startOffset,b===r?r.endContainer:b.startContainer,b===r?r.endOffset:b.startOffset);
  return 'clipped';
})()
"""
}
