#include <cmath>
#include <cstdio>
#include <mutex>

#include "ProgressMonitorConsole.h"

ProgressMonitorConsole::ProgressMonitorConsole()
    : currentPercent(0.0), jumpPercent(2.0), lastPrintedLabel(0)
{
}

void ProgressMonitorConsole::begin()
{
    std::lock_guard<std::mutex> guard(lock);
    currentPercent = 0;
    lastPrintedLabel = 0;
    jumpPercent = 2;
    std::printf("[ 0%% ");
    std::fflush(stdout);
}

void ProgressMonitorConsole::end()
{
    std::lock_guard<std::mutex> guard(lock);
    currentPercent = 100;
    std::printf(" 100%% ]\n");
    std::fflush(stdout);
}

bool ProgressMonitorConsole::testLabelLimit(int limit)
{
    if (limit == lastPrintedLabel) {
        return false;
    }

    if (currentPercent - 6 * jumpPercent / 10 < limit
        && currentPercent + 6 * jumpPercent / 10 > limit) {
        std::printf(" %d%% ", limit);
        lastPrintedLabel = limit;
        return true;
    }
    return false;
}

void ProgressMonitorConsole::update(
    double minValue,
    double maxValue,
    double currentValue)
{
    std::lock_guard<std::mutex> guard(lock);
    if (std::abs(maxValue - minValue) < 1e-12) {
        return;
    }

    double value = 100 * (currentValue - minValue) / (maxValue - minValue);
    while (currentPercent + jumpPercent < value) {
        currentPercent += jumpPercent;
        if (!testLabelLimit(25)
            && !testLabelLimit(50)
            && !testLabelLimit(75)) {
            std::printf("-");
        }
    }
    std::fflush(stdout);
}

double ProgressMonitorConsole::getCurrentPercent()
{
    std::lock_guard<std::mutex> guard(lock);
    return currentPercent;
}
