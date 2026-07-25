#ifndef TRACE_LOG_SPLITTER_PROGRESS_MONITOR_CONSOLE_H
#define TRACE_LOG_SPLITTER_PROGRESS_MONITOR_CONSOLE_H

#include <mutex>

// Adapted from VITRAL's
// vsdk/toolkit/gui/feedback/ProgressMonitorConsole.
class ProgressMonitorConsole {
private:
    std::mutex lock;
    double currentPercent;
    double jumpPercent;
    int lastPrintedLabel;

    bool testLabelLimit(int limit);

public:
    ProgressMonitorConsole();

    void begin();
    void end();
    void update(double minValue, double maxValue, double currentValue);
    double getCurrentPercent();
};

#endif
