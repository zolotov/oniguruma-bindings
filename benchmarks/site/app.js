(function () {
  'use strict';

  var SUITE_NAMES = { jni: 'JNI', ffm: 'FFM' };
  var SUITE_ORDER = { jni: 0, ffm: 1 };
  var SVG_NS = 'http://www.w3.org/2000/svg';

  // "?pr=<number>" switches the dashboard to a pull-request view: the same layout,
  // fed from data/prs/<n>/ where history.json holds only that PR's runs.
  var prParam = new URLSearchParams(window.location.search).get('pr');
  var activePr = prParam && /^\d+$/.test(prParam) ? prParam : null;
  var dataBase = activePr ? 'data/prs/' + activePr + '/' : 'data/';

  function suiteName(suite) {
    return SUITE_NAMES[suite] || suite;
  }

  function suiteIndex(suite) {
    return suite in SUITE_ORDER ? SUITE_ORDER[suite] : 99;
  }

  function measurementKey(m) {
    return m.suite + '::' + m.name + '::' + m.unit;
  }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined) node.textContent = text;
    return node;
  }

  function svgEl(tag, attrs) {
    var node = document.createElementNS(SVG_NS, tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (name) {
        node.setAttribute(name, attrs[name]);
      });
    }
    return node;
  }

  function link(href, text) {
    if (!/^https?:\/\//.test(href)) return document.createTextNode(text);
    var a = document.createElement('a');
    a.href = href;
    a.textContent = text;
    return a;
  }

  // For our own static hrefs; link() deliberately rejects non-http URLs from data.
  function relativeLink(href, text) {
    var a = document.createElement('a');
    a.href = href;
    a.textContent = text;
    return a;
  }

  function formatNumber(value, fractionDigits) {
    return value.toLocaleString('en-US', {
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits
    });
  }

  function valueFractionDigits(m) {
    if (m.unit === 'bytes') return 0;
    var abs = Math.abs(m.value);
    if (abs >= 100) return 2;
    if (abs >= 10) return 3;
    if (abs >= 1) return 4;
    return 5;
  }

  function formatValue(m) {
    return formatNumber(m.value, valueFractionDigits(m)) + ' ' + m.unit;
  }

  function formatDelta(entry) {
    if (!entry || !entry.baseline) return 'new';
    if (entry.deltaRatio === undefined || entry.deltaRatio === null) return 'n/a';
    if (Math.abs(entry.deltaRatio) < 1e-4) return '0.00%';
    var percent = entry.deltaRatio * 100;
    var formatted = (percent >= 0 ? '+' : '') + formatNumber(percent, 2) + '%';
    return entry.change === 'unchanged' ? formatted + ' (noise)' : formatted;
  }

  function deltaClass(entry) {
    if (!entry) return 'delta-neutral';
    if (entry.change === 'improvement') return 'delta-improvement';
    if (entry.change === 'regression') return 'delta-regression';
    return 'delta-neutral';
  }

  // Improvement and regression differ only in hue, which fails WCAG 1.4.1 and is invisible to
  // anyone who cannot distinguish the two. Name the status for assistive tech and add a glyph.
  var DELTA_MARKS = { improvement: '▲', regression: '▼' };

  function deltaCellContent(entry) {
    var span = el('span', deltaClass(entry), formatDelta(entry));
    var change = entry && entry.change;
    var mark = DELTA_MARKS[change];
    if (mark) {
      var glyph = el('span', 'delta-mark', mark + ' ');
      glyph.setAttribute('aria-hidden', 'true');
      span.insertBefore(glyph, span.firstChild);
      span.setAttribute('aria-label', change + ': ' + formatDelta(entry));
    }
    return span;
  }

  function shortDate(isoInstant) {
    return isoInstant ? isoInstant.slice(0, 10) : '';
  }

  function loadData() {
    // data.js only embeds the main-branch payload, so PR views always fetch.
    if (!activePr && window.BENCHMARK_DATA) return Promise.resolve(window.BENCHMARK_DATA);
    var names = ['latest', 'comparison', 'history'];
    return Promise.all(names.map(function (name) {
      return fetch(dataBase + name + '.json').then(function (response) {
        if (!response.ok) throw new Error('HTTP ' + response.status + ' for ' + dataBase + name + '.json');
        return response.json();
      });
    })).then(function (parts) {
      return { latest: parts[0], comparison: parts[1], history: parts[2] };
    });
  }

  function buildHistorySeries(history) {
    var seriesByKey = new Map();
    (history.runs || []).forEach(function (run) {
      (run.measurements || []).forEach(function (m) {
        var key = measurementKey(m);
        if (!seriesByKey.has(key)) seriesByKey.set(key, []);
        seriesByKey.get(key).push({
          value: m.value,
          lower: m.lowerValue,
          upper: m.upperValue,
          run: run
        });
      });
    });
    return seriesByKey;
  }

  /* ---------- metadata ---------- */

  function metaLine(label, valueNode) {
    var row = el('div');
    row.appendChild(el('dt', null, label));
    var dd = el('dd');
    if (typeof valueNode === 'string') {
      dd.textContent = valueNode;
    } else {
      dd.appendChild(valueNode);
    }
    row.appendChild(dd);
    return row;
  }

  function renderMeta(data) {
    var list = document.getElementById('meta-list');
    var meta = data.latest;
    var baseline = data.comparison.baseline;
    if (activePr) {
      document.title += ' — PR #' + activePr;
      var heading = document.querySelector('.page-header h1');
      if (heading) heading.textContent += ' — PR #' + activePr;
      list.appendChild(metaLine('Pull request', '#' + activePr + (meta.refName ? ' (' + meta.refName + ')' : '')));
      list.appendChild(metaLine('View', relativeLink('./', 'main dashboard')));
    }
    list.appendChild(metaLine('Generated', meta.generatedAt || 'n/a'));
    if (meta.refName) list.appendChild(metaLine('Ref', meta.refName));
    if (meta.commitSha) {
      list.appendChild(metaLine('Commit', meta.commitUrl
        ? link(meta.commitUrl, meta.commitSha.slice(0, 7))
        : meta.commitSha.slice(0, 7)));
    }
    if (meta.runUrl) list.appendChild(metaLine('Run', link(meta.runUrl, 'workflow')));
    list.appendChild(metaLine('Baseline', baseline
      ? [baseline.commitSha ? baseline.commitSha.slice(0, 7) : null, baseline.generatedAt]
          .filter(Boolean).join(' · ')
      : 'none (first run)'));
    list.appendChild(metaLine('Significance', 'CI overlap' +
      (typeof data.comparison.significanceThreshold === 'number'
        ? ' (±' + formatNumber(data.comparison.significanceThreshold * 100, 1) + '% fallback)'
        : '')));
    list.appendChild(metaLine('History', (data.history.runs || []).length + ' runs'));
  }

  /* ---------- pull request index ---------- */

  function renderPrIndex() {
    var container = document.getElementById('pr-list');
    if (!container || activePr) return Promise.resolve();
    // The index only exists once a PR run has been published; over file:// the
    // fetch fails and the section simply stays hidden. Returns a promise so the caller can
    // reveal the shell only once this has settled.
    return fetch('data/prs/index.json').then(function (response) {
      if (!response.ok) throw new Error('HTTP ' + response.status);
      return response.json();
    }).then(function (index) {
      var prs = (index.prs || []).slice().sort(function (a, b) { return b.number - a.number; });
      if (prs.length === 0) return;

      var panel = el('section', 'panel');
      var header = el('div', 'panel-header');
      header.appendChild(el('h2', null, 'Open Pull Requests'));
      header.appendChild(el('span', null, prs.length + (prs.length === 1 ? ' PR' : ' PRs')));
      panel.appendChild(header);

      var wrap = el('div', 'table-wrap');
      var table = el('table');
      var thead = el('thead');
      var headRow = el('tr');
      ['PR', 'Branch', 'Runs', 'Last run'].forEach(function (label) {
        headRow.appendChild(el('th', null, label));
      });
      thead.appendChild(headRow);
      table.appendChild(thead);
      var tbody = el('tbody');
      prs.forEach(function (pr) {
        var row = el('tr');
        var prCell = el('td');
        prCell.appendChild(relativeLink('?pr=' + pr.number, '#' + pr.number));
        row.appendChild(prCell);
        var branchCell = el('td');
        branchCell.appendChild(el('code', null, pr.refName || ''));
        row.appendChild(branchCell);
        row.appendChild(el('td', 'numeric', String(pr.runs || 0)));
        row.appendChild(el('td', 'numeric', shortDate(pr.updatedAt)));
        tbody.appendChild(row);
      });
      table.appendChild(tbody);
      wrap.appendChild(table);
      panel.appendChild(wrap);
      container.appendChild(panel);
      container.hidden = false;
    }).catch(function () { /* no published PR index */ });
  }

  /* ---------- change panels ---------- */

  function topEntries(entries, change) {
    return entries
      .filter(function (entry) {
        return entry.change === change && typeof entry.deltaRatio === 'number';
      })
      .sort(function (a, b) { return Math.abs(b.deltaRatio) - Math.abs(a.deltaRatio); })
      .slice(0, 8);
  }

  function changeTable(entries) {
    var wrap = el('div', 'table-wrap');
    var table = el('table');
    var thead = el('thead');
    var headRow = el('tr');
    ['Suite', 'Benchmark', 'Current', 'Baseline', 'Delta'].forEach(function (label) {
      headRow.appendChild(el('th', null, label));
    });
    thead.appendChild(headRow);
    table.appendChild(thead);
    var tbody = el('tbody');
    entries.forEach(function (entry) {
      var row = el('tr');
      row.appendChild(el('td', 'suite-label', suiteName(entry.current.suite)));
      var nameCell = el('td');
      nameCell.appendChild(el('code', null, entry.current.displayName));
      row.appendChild(nameCell);
      row.appendChild(el('td', 'numeric', formatValue(entry.current)));
      row.appendChild(el('td', 'numeric', entry.baseline ? formatValue(entry.baseline) : 'new'));
      var deltaCell = el('td', 'numeric');
      deltaCell.appendChild(deltaCellContent(entry));
      row.appendChild(deltaCell);
      tbody.appendChild(row);
    });
    table.appendChild(tbody);
    wrap.appendChild(table);
    return wrap;
  }

  function changePanel(title, entries, emptyText) {
    var panel = el('section', 'panel');
    var header = el('div', 'panel-header');
    header.appendChild(el('h2', null, title));
    panel.appendChild(header);
    if (entries.length === 0) {
      panel.appendChild(el('p', 'empty-state', emptyText));
    } else {
      panel.appendChild(changeTable(entries));
    }
    return panel;
  }

  function renderChanges(data) {
    var container = document.getElementById('changes');
    // Regression/improvement panels only make sense when this snapshot is a
    // comparison against a baseline: pull-request runs and local seeded runs.
    // On the published main dashboard (push/workflow_dispatch) the trend
    // charts carry that information, so the panels are dropped.
    var eventName = data.latest.eventName;
    var isComparisonContext = !eventName || eventName === 'pull_request';
    if (!isComparisonContext || !data.comparison.baseline) {
      container.hidden = true;
      return;
    }
    var entries = data.comparison.entries || [];
    container.appendChild(changePanel(
      'Largest Regressions',
      topEntries(entries, 'regression'),
      'No significant regressions relative to the baseline.'
    ));
    container.appendChild(changePanel(
      'Largest Improvements',
      topEntries(entries, 'improvement'),
      'No significant improvements relative to the baseline.'
    ));
  }

  /* ---------- JNI vs FFM ---------- */

  function comparisonPairs(measurements) {
    // The two bindings run the same JMH benchmarks (same class and method names),
    // so measurements pair up by display name across the jni and ffm suites.
    var pairs = new Map();
    measurements.forEach(function (m) {
      if (m.suite !== 'jni' && m.suite !== 'ffm') return;
      if (!pairs.has(m.displayName)) pairs.set(m.displayName, {});
      pairs.get(m.displayName)[m.suite] = m;
    });
    var rows = [];
    pairs.forEach(function (pair, base) {
      if (!pair.jni || !pair.ffm) return;
      // Pairing is by display name, so a unit mismatch would divide e.g. ops/ms by ops/s and
      // render a confident-looking 1000x. Skip rather than publish a meaningless ratio.
      if (pair.jni.unit !== pair.ffm.unit) {
        if (typeof console !== 'undefined' && console.warn) {
          console.warn('Skipping ' + base + ': JNI reports ' + pair.jni.unit +
            ' but FFM reports ' + pair.ffm.unit + '.');
        }
        return;
      }
      rows.push({ base: base, jni: pair.jni, ffm: pair.ffm });
    });
    rows.sort(function (a, b) { return a.base < b.base ? -1 : a.base > b.base ? 1 : 0; });
    return rows;
  }

  function ratioClass(ratio, biggerIsBetter) {
    if (!isFinite(ratio)) return 'delta-neutral';
    // ratio is ffm / jni, so a ratio above 1 means FFM scores higher -- which is only an
    // improvement when a higher score is better for this unit (throughput, not time/op).
    if (biggerIsBetter ? ratio >= 1.03 : ratio <= 0.97) return 'delta-improvement';
    if (biggerIsBetter ? ratio <= 0.97 : ratio >= 1.03) return 'delta-regression';
    return 'delta-neutral';
  }

  function comparisonHeaderCell(label, swatchClass) {
    var th = el('th');
    if (swatchClass) th.appendChild(el('span', 'key-swatch ' + swatchClass));
    th.appendChild(document.createTextNode(label));
    return th;
  }

  function renderImplComparison(data) {
    var container = document.getElementById('impl-comparison');
    var rows = comparisonPairs(data.latest.measurements || []);
    if (rows.length === 0) return;

    var nav = document.getElementById('suite-nav');
    var navLink = document.createElement('a');
    navLink.href = '#impl-comparison';
    navLink.textContent = 'JNI vs FFM';
    nav.appendChild(navLink);

    var section = el('section', 'suite-section comparison-section');
    var header = el('div', 'suite-header');
    var headerText = el('div');
    headerText.appendChild(el('p', 'eyebrow', 'Bindings'));
    headerText.appendChild(el('h2', null, 'JNI vs FFM'));
    header.appendChild(headerText);
    var counter = el('span', null, rows.length + ' pairs');
    header.appendChild(counter);
    section.appendChild(header);
    section.dataset.total = rows.length + ' pairs';

    var tableWrap = el('div', 'table-wrap');
    var table = el('table', 'suite-table');
    var thead = el('thead');
    var headRow = el('tr');
    headRow.appendChild(comparisonHeaderCell('Benchmark', null));
    headRow.appendChild(comparisonHeaderCell('JNI', 'key-jni'));
    headRow.appendChild(comparisonHeaderCell('FFM', 'key-ffm'));
    headRow.appendChild(comparisonHeaderCell('FFM / JNI', null));
    headRow.appendChild(comparisonHeaderCell('Throughput', null));
    thead.appendChild(headRow);
    table.appendChild(thead);

    var tbody = el('tbody');
    rows.forEach(function (pair) {
      var row = el('tr', 'comparison-row');
      row.dataset.filterText = pair.base.toLowerCase();

      var nameCell = el('td');
      nameCell.appendChild(el('code', null, pair.base));
      row.appendChild(nameCell);
      row.appendChild(el('td', 'numeric', formatValue(pair.jni)));
      row.appendChild(el('td', 'numeric', formatValue(pair.ffm)));

      var ratio = pair.jni.value !== 0 ? pair.ffm.value / pair.jni.value : NaN;
      var ratioCell = el('td', 'numeric');
      var ratioKind = ratioClass(ratio, pair.ffm.biggerIsBetter !== false);
      var ratioSpan = el('span', ratioKind,
        isFinite(ratio) ? formatNumber(ratio, 2) + '×' : 'n/a');
      // As with the delta column, do not rely on hue alone to say which way this reads.
      if (ratioKind === 'delta-improvement' || ratioKind === 'delta-regression') {
        var faster = ratioKind === 'delta-improvement';
        ratioSpan.setAttribute('aria-label',
          formatNumber(ratio, 2) + '× — FFM is ' + (faster ? 'better' : 'worse') + ' than JNI here');
        ratioSpan.title = 'FFM is ' + (faster ? 'better' : 'worse') + ' than JNI for this benchmark';
      }
      ratioCell.appendChild(ratioSpan);
      row.appendChild(ratioCell);

      var barsCell = el('td');
      var bars = el('div', 'compare-bars');
      var maxValue = Math.max(pair.jni.value, pair.ffm.value) || 1;
      var jniBar = el('span', 'bar bar-jni');
      jniBar.style.width = (pair.jni.value / maxValue * 100).toFixed(1) + '%';
      jniBar.title = 'JNI: ' + formatValue(pair.jni);
      var ffmBar = el('span', 'bar bar-ffm');
      ffmBar.style.width = (pair.ffm.value / maxValue * 100).toFixed(1) + '%';
      ffmBar.title = 'FFM: ' + formatValue(pair.ffm);
      bars.appendChild(jniBar);
      bars.appendChild(ffmBar);
      barsCell.appendChild(bars);
      row.appendChild(barsCell);

      tbody.appendChild(row);
    });
    table.appendChild(tbody);
    tableWrap.appendChild(table);
    section.appendChild(tableWrap);
    container.appendChild(section);
  }

  /* ---------- sparklines ---------- */

  function sparkline(points) {
    if (points.length < 2) {
      return el('span', 'sparkline-empty', points.length === 1 ? '1 run' : 'n/a');
    }
    var width = 152;
    var height = 36;
    var padding = 4;
    var values = points.map(function (p) { return p.value; });
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    var spread = max - min;
    // A perfectly flat series used to fall out as (value - min) / 1 === 0, drawing the line
    // along the bottom edge -- i.e. looking like a series pinned at its minimum. Centre it.
    var isFlat = !(spread > 0);
    var step = (width - padding * 2) / (points.length - 1);
    var coords = values.map(function (value, index) {
      var x = padding + step * index;
      var y = isFlat
        ? height / 2
        : padding + (height - padding * 2) * (1 - (value - min) / spread);
      return [x, y];
    });
    var svg = svgEl('svg', {
      'class': 'sparkline',
      viewBox: '0 0 ' + width + ' ' + height,
      role: 'img',
      'aria-label': 'Trend over ' + points.length + ' runs'
    });
    svg.appendChild(svgEl('polyline', {
      points: coords.map(function (c) { return c[0].toFixed(2) + ',' + c[1].toFixed(2); }).join(' ')
    }));
    var last = coords[coords.length - 1];
    svg.appendChild(svgEl('circle', {
      'class': 'spark-end',
      cx: last[0].toFixed(2),
      cy: last[1].toFixed(2),
      r: 3.2
    }));
    return svg;
  }

  /* ---------- history detail chart ---------- */

  function niceTicks(min, max, count) {
    if (min === max) {
      var pad = Math.abs(min) > 0 ? Math.abs(min) * 0.05 : 1;
      min -= pad;
      max += pad;
    }
    var span = max - min;
    var rawStep = span / count;
    var magnitude = Math.pow(10, Math.floor(Math.log10(rawStep)));
    var step = magnitude;
    [1, 2, 2.5, 5, 10].some(function (factor) {
      if (magnitude * factor >= rawStep) {
        step = magnitude * factor;
        return true;
      }
      return false;
    });
    var start = Math.floor(min / step) * step;
    var ticks = [];
    for (var tick = start; ; tick += step) {
      ticks.push(tick);
      if (tick >= max) break;
    }
    return ticks;
  }

  function formatTick(value, unit) {
    if (unit === 'bytes' || Math.abs(value) >= 1000) {
      if (Math.abs(value) >= 1e6) return formatNumber(value / 1e6, 1) + 'M';
      if (Math.abs(value) >= 1e3) return formatNumber(value / 1e3, 1) + 'K';
    }
    var digits = Math.abs(value) >= 100 ? 0 : Math.abs(value) >= 1 ? 1 : 3;
    return formatNumber(value, digits);
  }

  function detailChart(measurement, points) {
    var card = el('div', 'chart-card');
    var header = el('div', 'chart-card-header');
    var title = el('h3', null, measurement.displayName);
    header.appendChild(title);
    header.appendChild(el('span', null, points.length + ' runs · ' + measurement.unit));
    card.appendChild(header);

    if (points.length < 2) {
      card.appendChild(el('p', 'empty-state', 'Not enough history for a chart yet — this measurement appears in ' + points.length + ' published run(s).'));
      return card;
    }

    var W = 680;
    var H = 240;
    var margin = { top: 14, right: 18, bottom: 30, left: 64 };
    var innerW = W - margin.left - margin.right;
    var innerH = H - margin.top - margin.bottom;

    var values = [];
    points.forEach(function (p) {
      values.push(p.value);
      if (typeof p.lower === 'number') values.push(p.lower);
      if (typeof p.upper === 'number') values.push(p.upper);
    });
    var min = Math.min.apply(null, values);
    var max = Math.max.apply(null, values);
    var ticks = niceTicks(min, max, 4);
    var yMin = ticks[0];
    var yMax = ticks[ticks.length - 1];
    var xAt = function (index) {
      return margin.left + (points.length === 1 ? innerW / 2 : innerW * index / (points.length - 1));
    };
    var yAt = function (value) {
      return margin.top + innerH * (1 - (value - yMin) / (yMax - yMin || 1));
    };

    var wrap = el('div', 'chart-wrap');
    // Not role="img": that makes the subtree presentational, yet this element is focusable and
    // responds to arrow keys. role="group" keeps it in the accessibility tree as an interactive
    // container, and the tooltip below is a live region so keyboard stepping is announced.
    var svg = svgEl('svg', {
      viewBox: '0 0 ' + W + ' ' + H,
      role: 'group',
      tabindex: '0',
      'aria-label': measurement.displayName + ', ' + points.length + ' runs, latest ' +
        formatValue(measurement) + '. Use left and right arrow keys to inspect individual runs.'
    });

    var grid = svgEl('g', { 'class': 'chart-grid' });
    var axis = svgEl('g', { 'class': 'chart-axis' });
    ticks.forEach(function (tick) {
      var y = yAt(tick);
      grid.appendChild(svgEl('line', { x1: margin.left, x2: W - margin.right, y1: y, y2: y }));
      var label = svgEl('text', { x: margin.left - 8, y: y + 4, 'text-anchor': 'end' });
      label.textContent = formatTick(tick, measurement.unit);
      axis.appendChild(label);
    });
    var firstDate = svgEl('text', { x: margin.left, y: H - 8, 'text-anchor': 'start' });
    firstDate.textContent = shortDate(points[0].run.generatedAt);
    axis.appendChild(firstDate);
    var lastDate = svgEl('text', { x: W - margin.right, y: H - 8, 'text-anchor': 'end' });
    lastDate.textContent = shortDate(points[points.length - 1].run.generatedAt);
    axis.appendChild(lastDate);
    svg.appendChild(grid);
    svg.appendChild(axis);

    var hasBand = points.some(function (p) {
      return typeof p.lower === 'number' && typeof p.upper === 'number';
    });
    if (hasBand) {
      var upperPath = points.map(function (p, index) {
        var value = typeof p.upper === 'number' ? p.upper : p.value;
        return xAt(index).toFixed(2) + ',' + yAt(value).toFixed(2);
      });
      var lowerPath = points.map(function (p, index) {
        var value = typeof p.lower === 'number' ? p.lower : p.value;
        return xAt(index).toFixed(2) + ',' + yAt(value).toFixed(2);
      }).reverse();
      svg.appendChild(svgEl('polygon', {
        'class': 'chart-band',
        points: upperPath.concat(lowerPath).join(' ')
      }));
    } else {
      var areaPoints = points.map(function (p, index) {
        return xAt(index).toFixed(2) + ',' + yAt(p.value).toFixed(2);
      });
      areaPoints.push(xAt(points.length - 1).toFixed(2) + ',' + (margin.top + innerH).toFixed(2));
      areaPoints.push(xAt(0).toFixed(2) + ',' + (margin.top + innerH).toFixed(2));
      svg.appendChild(svgEl('polygon', { 'class': 'chart-area', points: areaPoints.join(' ') }));
    }

    svg.appendChild(svgEl('polyline', {
      'class': 'chart-line',
      points: points.map(function (p, index) {
        return xAt(index).toFixed(2) + ',' + yAt(p.value).toFixed(2);
      }).join(' ')
    }));

    var lastIndex = points.length - 1;
    svg.appendChild(svgEl('circle', {
      'class': 'chart-dot',
      cx: xAt(lastIndex).toFixed(2),
      cy: yAt(points[lastIndex].value).toFixed(2),
      r: 4
    }));

    var crosshair = svgEl('line', {
      'class': 'chart-crosshair',
      y1: margin.top,
      y2: margin.top + innerH,
      visibility: 'hidden'
    });
    var hoverDot = svgEl('circle', { 'class': 'chart-dot', r: 4, visibility: 'hidden' });
    svg.appendChild(crosshair);
    svg.appendChild(hoverDot);

    // The tooltip lives outside the SVG, so make it a live region: without this, a keyboard
    // user stepping through runs with the arrow keys gets no feedback at all.
    var tooltip = el('div', 'chart-tooltip');
    tooltip.setAttribute('role', 'status');
    tooltip.setAttribute('aria-live', 'polite');
    tooltip.hidden = true;
    var tooltipValue = el('strong', 'tooltip-value');
    var tooltipMeta = el('span', 'tooltip-meta');
    tooltip.appendChild(tooltipValue);
    tooltip.appendChild(tooltipMeta);

    function showIndex(index) {
      var p = points[index];
      var x = xAt(index);
      var y = yAt(p.value);
      crosshair.setAttribute('x1', x);
      crosshair.setAttribute('x2', x);
      crosshair.setAttribute('visibility', 'visible');
      hoverDot.setAttribute('cx', x);
      hoverDot.setAttribute('cy', y);
      hoverDot.setAttribute('visibility', 'visible');
      tooltipValue.textContent = formatNumber(p.value, valueFractionDigits({ unit: measurement.unit, value: p.value })) + ' ' + measurement.unit;
      var metaParts = [shortDate(p.run.generatedAt)];
      if (p.run.commitSha) metaParts.push(p.run.commitSha.slice(0, 7));
      if (p.run.refName) metaParts.push(p.run.refName);
      tooltipMeta.textContent = metaParts.join(' · ');
      var xPercent = x / W * 100;
      tooltip.style.left = xPercent + '%';
      tooltip.style.top = (y / H * 100) + '%';
      tooltip.style.transform = xPercent < 20 ? 'translate(8px, -120%)'
        : xPercent > 80 ? 'translate(calc(-100% - 8px), -120%)'
        : 'translate(-50%, -130%)';
      tooltip.hidden = false;
      return index;
    }

    function hideHover() {
      crosshair.setAttribute('visibility', 'hidden');
      hoverDot.setAttribute('visibility', 'hidden');
      tooltip.hidden = true;
    }

    var activeIndex = lastIndex;
    // getBoundingClientRect forces a synchronous layout, so measure once when the pointer
    // arrives rather than on every pointermove. Anything that can move or resize the chart
    // invalidates the cached rect.
    var cachedRect = null;
    function invalidateRect() { cachedRect = null; }
    svg.addEventListener('pointerenter', invalidateRect);
    window.addEventListener('resize', invalidateRect);
    window.addEventListener('scroll', invalidateRect, true);
    svg.addEventListener('pointermove', function (event) {
      if (!cachedRect) cachedRect = svg.getBoundingClientRect();
      var rect = cachedRect;
      if (!rect.width) return;
      var viewX = (event.clientX - rect.left) / rect.width * W;
      var ratio = (viewX - margin.left) / innerW;
      var index = Math.round(ratio * (points.length - 1));
      activeIndex = showIndex(Math.max(0, Math.min(points.length - 1, index)));
    });
    svg.addEventListener('pointerleave', function () {
      invalidateRect();
      if (document.activeElement !== svg) hideHover();
    });
    svg.addEventListener('focus', function () {
      activeIndex = showIndex(activeIndex);
    });
    svg.addEventListener('blur', hideHover);
    svg.addEventListener('keydown', function (event) {
      if (event.key === 'ArrowLeft') activeIndex = showIndex(Math.max(0, activeIndex - 1));
      else if (event.key === 'ArrowRight') activeIndex = showIndex(Math.min(points.length - 1, activeIndex + 1));
      else if (event.key === 'Home') activeIndex = showIndex(0);
      else if (event.key === 'End') activeIndex = showIndex(points.length - 1);
      else if (event.key === 'Escape') hideHover();
      else return;
      event.preventDefault();
    });

    wrap.appendChild(svg);
    wrap.appendChild(tooltip);
    card.appendChild(wrap);
    card.appendChild(el('p', 'chart-hint', 'Hover or focus the chart and use ←/→ to inspect runs. The shaded band is the reported confidence interval.'));
    return card;
  }

  /* ---------- suite tables ---------- */

  function benchmarkRow(measurement, comparisonEntry, seriesPoints, columns) {
    var row = el('tr', 'benchmark-row');
    row.dataset.filterText = (measurement.displayName + ' ' + measurement.group).toLowerCase();

    var nameCell = el('td');
    var toggle = el('button', 'row-toggle');
    toggle.type = 'button';
    toggle.setAttribute('aria-expanded', 'false');
    toggle.appendChild(el('code', null, measurement.displayName));
    nameCell.appendChild(toggle);
    nameCell.appendChild(el('span', 'group-label', measurement.group));
    row.appendChild(nameCell);

    row.appendChild(el('td', 'numeric', formatValue(measurement)));
    row.appendChild(el('td', 'numeric',
      comparisonEntry && comparisonEntry.baseline ? formatValue(comparisonEntry.baseline) : 'new'));
    var deltaCell = el('td', 'numeric');
    deltaCell.appendChild(deltaCellContent(comparisonEntry));
    row.appendChild(deltaCell);
    var trendCell = el('td');
    trendCell.appendChild(sparkline(seriesPoints));
    row.appendChild(trendCell);

    var detailRow = el('tr', 'detail-row');
    detailRow.hidden = true;
    // aria-expanded is only meaningful if the controlled region is identified.
    detailRow.id = 'detail-' + measurementKey(measurement).replace(/[^A-Za-z0-9_-]+/g, '-');
    toggle.setAttribute('aria-controls', detailRow.id);
    var detailCell = el('td');
    detailCell.colSpan = columns;
    detailRow.appendChild(detailCell);

    function setExpanded(expanded) {
      toggle.setAttribute('aria-expanded', String(expanded));
      detailRow.hidden = !expanded;
      if (expanded && !detailCell.firstChild) {
        detailCell.appendChild(detailChart(measurement, seriesPoints));
      }
    }

    row.addEventListener('click', function (event) {
      if (event.target.closest('a') || event.target.closest('.detail-row')) return;
      setExpanded(detailRow.hidden);
    });
    toggle.addEventListener('click', function (event) {
      event.stopPropagation();
      setExpanded(detailRow.hidden);
    });

    return { row: row, detailRow: detailRow };
  }

  function renderSuites(data, seriesByKey) {
    var container = document.getElementById('suites');
    var nav = document.getElementById('suite-nav');
    var comparisonByKey = new Map();
    (data.comparison.entries || []).forEach(function (entry) {
      comparisonByKey.set(measurementKey(entry.current), entry);
    });

    var measurements = (data.latest.measurements || []).slice();
    var suites = measurements
      .map(function (m) { return m.suite; })
      .filter(function (suite, index, all) { return all.indexOf(suite) === index; })
      .sort(function (a, b) { return suiteIndex(a) - suiteIndex(b); });

    suites.forEach(function (suite) {
      var navLink = document.createElement('a');
      navLink.href = '#suite-' + suite;
      navLink.textContent = suiteName(suite);
      nav.appendChild(navLink);

      var suiteMeasurements = measurements.filter(function (m) { return m.suite === suite; });
      var section = el('section', 'suite-section');
      section.id = 'suite-' + suite;
      var header = el('div', 'suite-header');
      var headerText = el('div');
      headerText.appendChild(el('p', 'eyebrow', suiteName(suite)));
      headerText.appendChild(el('h2', null, suiteName(suite) + ' measurements'));
      header.appendChild(headerText);
      var counter = el('span', null, suiteMeasurements.length + ' measurements');
      header.appendChild(counter);
      section.appendChild(header);
      section.dataset.total = suiteMeasurements.length + ' measurements';

      var tableWrap = el('div', 'table-wrap');
      var table = el('table', 'suite-table');
      var thead = el('thead');
      var headRow = el('tr');
      ['Benchmark', 'Current', 'Baseline', 'Delta', 'Trend'].forEach(function (label) {
        headRow.appendChild(el('th', null, label));
      });
      thead.appendChild(headRow);
      table.appendChild(thead);
      var tbody = el('tbody');
      suiteMeasurements.forEach(function (measurement) {
        var key = measurementKey(measurement);
        var rows = benchmarkRow(
          measurement,
          comparisonByKey.get(key),
          seriesByKey.get(key) || [],
          5
        );
        tbody.appendChild(rows.row);
        tbody.appendChild(rows.detailRow);
      });
      table.appendChild(tbody);
      tableWrap.appendChild(table);
      section.appendChild(tableWrap);
      container.appendChild(section);
    });
  }

  function attachFilter() {
    var input = document.getElementById('filter-input');

    // The row set is fixed once rendering finishes, so resolve it -- and each row's toggle and
    // detail row -- once here instead of re-querying the whole document on every keystroke.
    var sections = [].map.call(document.querySelectorAll('.suite-section'), function (section) {
      var rows = [].map.call(
        section.querySelectorAll('tr.benchmark-row, tr.comparison-row'),
        function (row) {
          var detail = row.nextElementSibling;
          return {
            row: row,
            filterText: row.dataset.filterText || '',
            toggle: row.querySelector('.row-toggle'),
            detail: detail && detail.classList.contains('detail-row') ? detail : null
          };
        }
      );
      return {
        section: section,
        rows: rows,
        counter: section.querySelector('.suite-header span'),
        total: section.dataset.total
      };
    });

    function applyFilter() {
      var query = input.value.trim().toLowerCase();
      sections.forEach(function (entry) {
        var visible = 0;
        entry.rows.forEach(function (item) {
          var matches = query === '' || item.filterText.indexOf(query) !== -1;
          item.row.hidden = !matches;
          if (item.detail) {
            item.detail.hidden = !matches ||
              !item.toggle ||
              item.toggle.getAttribute('aria-expanded') !== 'true';
          }
          if (matches) visible += 1;
        });
        entry.counter.textContent = query === '' ? entry.total : visible + ' of ' + entry.total;
        entry.section.hidden = visible === 0;
      });
    }

    // Coalesce bursts of typing into one pass per frame.
    var pending = null;
    input.addEventListener('input', function () {
      if (pending !== null) return;
      pending = window.requestAnimationFrame(function () {
        pending = null;
        applyFilter();
      });
    });
  }

  // Both renders must settle before data-loading is cleared. renderPrIndex used to run
  // fire-and-forget, so its fetch typically resolved after the shell was revealed and the PR
  // table popped in afterwards -- the header-only intermediate frame the stylesheet claims
  // cannot happen.
  Promise.all([
    renderPrIndex(),
    loadData().then(function (data) {
      renderMeta(data);
      renderChanges(data);
      renderImplComparison(data);
      renderSuites(data, buildHistorySeries(data.history));
      attachFilter();
    }).catch(function (error) {
      console.error(error);
      document.getElementById('load-error').hidden = false;
      document.getElementById('toolbar').hidden = true;
    })
  ]).finally(function () {
    document.body.removeAttribute('data-loading');
  });
})();
