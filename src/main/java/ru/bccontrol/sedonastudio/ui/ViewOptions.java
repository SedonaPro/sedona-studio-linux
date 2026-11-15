package ru.bccontrol.sedonastudio.ui;

/**
 * How the diagram is shown: zoom, grid, snapping, overlap avoidance, value labels and signal
 * highlighting.
 */
final class ViewOptions {
  boolean highlightSignal;
  double zoom = 1;
  boolean snapGrid = true;
  boolean showGrid = true;
  boolean avoidOverlap = true;
  /** Send every change to the connected device at once, without the review dialog. */
  boolean autoApply;

  boolean showValues = true;
}
