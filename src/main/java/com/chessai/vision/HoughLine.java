package com.chessai.vision;

/** Line represented by x*cos(theta) + y*sin(theta) = rho. */
public final class HoughLine {
    public final double theta;
    public final double rho;
    public final double weight;
    public final boolean vertical;

    public HoughLine(double theta, double rho, double weight, boolean vertical) {
        this.theta = theta;
        this.rho = rho;
        this.weight = weight;
        this.vertical = vertical;
    }

    public double xAt(double y) {
        double sin = Math.sin(theta);
        double cos = Math.cos(theta);
        if (Math.abs(cos) < 1e-9) {
            return Double.NaN;
        }
        return (rho - y * sin) / cos;
    }

    public double yAt(double x) {
        double sin = Math.sin(theta);
        if (Math.abs(sin) < 1e-9) {
            return Double.NaN;
        }
        return (rho - x * Math.cos(theta)) / sin;
    }

    public double positionAtCenter(double centerX, double centerY) {
        return vertical ? xAt(centerY) : yAt(centerX);
    }

    public double distance(double x, double y) {
        return Math.abs(x * Math.cos(theta) + y * Math.sin(theta) - rho);
    }

    public double angleDegrees() {
        double degrees = Math.toDegrees(theta) % 180.0;
        if (degrees < 0) {
            degrees += 180.0;
        }
        return degrees;
    }

    public HoughLine scaled(double factor) {
        return new HoughLine(theta, rho * factor, weight, vertical);
    }
}
