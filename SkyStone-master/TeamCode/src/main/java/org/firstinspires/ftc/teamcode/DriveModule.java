package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.DcMotor;

import org.firstinspires.ftc.robotcore.external.Telemetry;

public class DriveModule {
    Robot robot;

    //TODO: make sure these motors are not flipped on your drive
    DcMotor motor1; //top motor
    DcMotor motor2; //bottom motor

    double lastM1Encoder;
    double lastM2Encoder;

    public final ModuleSide moduleSide;
    public final Vector2d positionVector; //position of module relative to robot COM (center of mass)

    //used for logic that allows robot to rotate modules as little as possible
    public boolean takingShortestPath = false;
    public boolean reversed = false;

    enum RotateModuleMode {
        DO_NOT_ROTATE_MODULES, ROTATE_MODULES
    }
    public RotateModuleMode rotateModuleMode = RotateModuleMode.ROTATE_MODULES;

    //important note about naming conventions below:
    // a MODULE rev is when the orientation of the module changes by 360 degrees
    // a WHEEL rev is when the wheel drives a distance equal to its circumference


    public final double TICKS_PER_MODULE_REV = 28 * (double)(60)/14 * (double)(48)/15 * (double)(82)/22; //ticks per MODULE revolution
    public final double DEGREES_PER_TICK = 360/TICKS_PER_MODULE_REV;

    public final double TICKS_PER_WHEEL_REV = 28 * (double)(60)/14 * (double)(48)/15 * (double)(82)/22 * (double)(14)/60; //ticks per WHEEL revolution

    public final double CM_WHEEL_DIAMETER = 3 * 2.54;
    public final double CM_PER_WHEEL_REV = CM_WHEEL_DIAMETER * Math.PI;
    public final double CM_PER_TICK = CM_PER_WHEEL_REV/TICKS_PER_WHEEL_REV;

    //used for scaling pivot component (see getPivotComponent() method)
    public final double ANGLE_OF_MAX_MODULE_ROTATION_POWER = 60;

    //if module is within this number of degrees from its target orientation, no pivot power will be applied
    public final double ALLOWED_MODULE_ORIENTATION_ERROR = 5;

    //TODO: tune this variable (see commented out section in TeleOp)
    public double ROT_ADVANTAGE = 1.7; //max rotation power divided by max translation power (scaling factor)

    //this variable is set to 0.7 because when in RUN_USING_ENCODERS mode, powers about ~0.7 are the same
    //setting to 1 may increase robot top speed, but may decrease accuracy
    public double MAX_MOTOR_POWER = 1.0; //was 0.7

    //unit vectors representing motors in the rotation power vs. translation power coordinate system
    //more documentation on this coming soon
    public Vector2d MOTOR_1_VECTOR = new Vector2d(1/Math.sqrt(2), 1/Math.sqrt(2)); //unit vector (MAY BE SWITCHED with below)
    public Vector2d MOTOR_2_VECTOR = new Vector2d(-1/Math.sqrt(2), 1/Math.sqrt(2)); //unit vector


    //variables used for path length distance tracking
    //this tracking is useful only for straight line paths
    //tracking robot x and y position is significantly more complicated, and will likely be added in a later version
    private double distanceTraveled; //distance traveled (delta s) since initial encoder state
    private double lastMotor1Encoder;
    private double lastMotor2Encoder;


    public DriveModule(Robot robot, ModuleSide moduleSide) {
        this.robot = robot;
        this.moduleSide = moduleSide;
        if (moduleSide == ModuleSide.RIGHT) {
            motor1 = robot.hardwareMap.dcMotor.get("rightTopMotor");
            motor2 = robot.hardwareMap.dcMotor.get("rightBottomMotor");
            positionVector = new Vector2d((double)18/2, 0); //points from robot center to right module
        } else {
            motor1 = robot.hardwareMap.dcMotor.get("leftTopMotor");
            motor2 = robot.hardwareMap.dcMotor.get("leftBottomMotor");
            positionVector = new Vector2d((double)-18/2, 0); //points from robot center to left module
        }

        lastMotor1Encoder = motor1.getCurrentPosition();
        lastMotor2Encoder = motor2.getCurrentPosition();

        //added (was undefined mode)
        motor1.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        motor2.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);

        motor1.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        motor2.setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);

        lastM1Encoder = motor1.getCurrentPosition();
        lastM2Encoder = motor1.getCurrentPosition();
    }


    public void updateTarget (Vector2d transVec, double rotMag) { //translation vector and rotation magnitude
        //converts robot heading to the angle type used by Vector2d class
        Angle convertedRobotHeading = robot.getRobotHeading().convertAngle(Angle.AngleType.NEG_180_TO_180_CARTESIAN);

        //converts the translation vector from a robot centric to a field centric one
        Vector2d transVecFC = transVec.rotate(convertedRobotHeading.getAngle()); //ADDED rotate 90

        //vector needed to rotate robot at the desired magnitude
        //based on positionVector of module (see definition for more info)
        Vector2d rotVec = positionVector.normalize(rotMag);//.rotate(90); //theoretically this should be rotated 90, not sure sure it doesn't need to be

        //combine desired robot translation and robot rotation to get goal vector for the module
        Vector2d targetVector = transVecFC.add(rotVec);

        //allows modules to reverse power instead of rotating 180 degrees
        //example: useful when going from driving forwards to driving backwards
        int directionMultiplier = 1;
        if (reversed) { //reverse direction of translation because module is reversed
            targetVector = targetVector.reflect();
            directionMultiplier = -1;
        }

        //calls method that will apply motor powers necessary to reach target vector in the best way possible, based on current position
        goToTarget(targetVector, directionMultiplier);

        robot.telemetry.addData(moduleSide + " REVERSED: ", reversed);
        robot.telemetry.addData(moduleSide + " Trans Vec FC: ", transVecFC);
        robot.telemetry.addData(moduleSide + " Rot Vec: ", rotVec);
    }

//    public void driveWhileAdjusting(double power) {
//        double angle = getCurrentOrientation().getAngle();
//        double powerDifference = RobotUtil.mapToAbs(angle, maxPower, maxAngle);
//        motor1.setPower(power - powerDifference / 2);
//        motor2.setPower(power + powerDifference / 2);
//    }

//    //with absolute heading rotation mode
//    public void updateTargetAbsRotation (Vector2d transVec, Angle targetHeading) { //translation vector and rotation magnitude
//        Angle robotHeading = new Angle(-robot.getRobotHeading(), Angle.AngleType.NEG_180_TO_180_HEADING);
//        double rotMag = getRotMag(targetHeading, robotHeading, new Angle(90, Angle.AngleType.NEG_180_TO_180_HEADING));
//        updateTarget(transVec, rotMag);
//    }

    //sets motor powers for robot to best approach given target vector
    public void goToTarget (Vector2d targetVector, int directionMultiplier) {
        //how much the module needs to translate (and in which direction)
        double moveComponent = targetVector.getMagnitude() * directionMultiplier;

        //how much the module needs to pivot (change its orientation)
        double pivotComponent;
        if (targetVector.getMagnitude() != 0) {
            pivotComponent = getPivotComponent(targetVector, getCurrentOrientation());
        } else {
            //if target vector is zero (joystick is within deadband) don't pivot modules
            pivotComponent = 0;
        }

        //vector in an (invented) coordinate system that represents desired (relative) module translation and module rotation
        Vector2d powerVector = new Vector2d(moveComponent, pivotComponent); //order very important here
        setMotorPowers(powerVector);

        robot.telemetry.addData(moduleSide + " Target Vector Angle: ", targetVector.getAngle());
        robot.telemetry.addData(moduleSide + " Power Vector: ", powerVector);
        robot.telemetry.addData(moduleSide + " Current orientation: ", getCurrentOrientation().getAngle());
    }


    //returns a scalar corresponding to how much power the module needs to apply to rotating
    //this is necessary because of the differential nature of a diff swerve drive
    public double getPivotComponent (Vector2d targetVector, Angle currentAngle) {
        Angle targetAngle = targetVector.getAngleAngle();
        double angleDiff = targetAngle.getDifference(currentAngle); //number from 0 to 180 (always positive)

        //allows module to rotate to the opposite position of (180 degrees away from) its target
        //if this is the fastest path, we need to indicate that the direction of translation should be reversed
        if (Math.abs(angleDiff) > 90) {
            if (!takingShortestPath) {
                reversed = !reversed; //reverse translation direction bc module is newly reversed
            }
            takingShortestPath = true;
        } else {
            takingShortestPath = false;
        }

        robot.telemetry.addData(moduleSide + " Angle diff (abs. value): ", angleDiff);
        Angle.Direction direction = currentAngle.directionTo(targetAngle);

        //CCW is negative for heading system
        if (rotateModuleMode == RotateModuleMode.DO_NOT_ROTATE_MODULES) return 0;

        if (angleDiff < ALLOWED_MODULE_ORIENTATION_ERROR) {
            //don't rotate module if it's currently within x degrees of its target orientation
            //avoids constant twitching of modules
            return 0;
        } else if (angleDiff > ANGLE_OF_MAX_MODULE_ROTATION_POWER) {
            //rotation power is maxed out if the difference is more than this angle
            if (direction == Angle.Direction.CLOCKWISE) return ROT_ADVANTAGE;
            else return -1 * ROT_ADVANTAGE;
        } else {
            //scale module rotation power based on set constants
            if (direction == Angle.Direction.CLOCKWISE) return angleDiff / ANGLE_OF_MAX_MODULE_ROTATION_POWER * ROT_ADVANTAGE;
            else return -1 * angleDiff / ANGLE_OF_MAX_MODULE_ROTATION_POWER * ROT_ADVANTAGE;
        }
    }


    //takes vector in power vector coordinate system
    // ^(x component is relative translation power and y component is relative MODULE rotation power)
    //calculates motor powers that will result in the desired ratio of module translation and module rotation
    //sets motors to appropriate powers
    public void setMotorPowers (Vector2d powerVector) {

        //this is one way to convert desired ratio of module translation and module rotation to motor powers
        //vectors are not strictly necessary for this, but made it easier to visualize
        //more documentation on this visualization method coming soon
        Vector2d motor1Unscaled = powerVector.projection(MOTOR_1_VECTOR);
        Vector2d motor2Unscaled = powerVector.projection(MOTOR_2_VECTOR);

        //makes sure no vector magnitudes exceed the maximum motor power
        Vector2d[] motorPowersScaled = Vector2d.batchNormalize(MAX_MOTOR_POWER, motor1Unscaled, motor2Unscaled);
        double motor1power = motorPowersScaled[0].getMagnitude();
        double motor2power = motorPowersScaled[1].getMagnitude();

        //this is to add sign to magnitude, which returns an absolute value
        if (motorPowersScaled[0].getAngle() != MOTOR_1_VECTOR.getAngle()) {
            motor1power *= -1;
        }
        if (motorPowersScaled[1].getAngle() != MOTOR_2_VECTOR.getAngle()) {
            motor2power *= -1;
        }

        robot.telemetry.addData(moduleSide + " Motor 1 Power: ", motor1power);
        robot.telemetry.addData(moduleSide + " Motor 2 Power: ", motor2power);
        motor1.setPower(motor1power);
        motor2.setPower(motor2power);
    }


    //for pure module rotation (usually used for precise driving in auto)
    public void rotateModule (Vector2d direction, boolean fieldCentric) {
        //converts robot heading to the angle type used by Vector2d class
        Angle convertedRobotHeading = robot.getRobotHeading().convertAngle(Angle.AngleType.NEG_180_TO_180_CARTESIAN);

        //pass 0 as moveComponent
        Vector2d directionFC = direction.rotate(convertedRobotHeading.getAngle());

        //ADDED
        if (reversed) { //reverse direction of translation because module is reversed
            directionFC = directionFC.reflect();
            direction = direction.reflect();
        }

        Vector2d powerVector;
        if (fieldCentric) {
            powerVector = new Vector2d(0, getPivotComponent(directionFC, getCurrentOrientation())); //order important here
        } else {
            powerVector = new Vector2d(0, getPivotComponent(direction, getCurrentOrientation())); //order important here
        }
        setMotorPowers(powerVector);
        robot.telemetry.addData(moduleSide + " Power Vector: ", powerVector);
        robot.telemetry.addData(moduleSide + " Current orientation: ", getCurrentOrientation().getAngle());
    }

    //does not need to be called at the start of every program
    //separate OpMode called ResetEncoders calls this method
    public void resetEncoders () {
        motor1.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        motor2.setMode(DcMotor.RunMode.STOP_AND_RESET_ENCODER);
        motor1.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        motor2.setMode(DcMotor.RunMode.RUN_USING_ENCODER);
    }

    //returns module orientation relative to ROBOT (not field) in degrees and NEG_180_TO_180_HEADING type
    public Angle getCurrentOrientation() {
        robot.telemetry.addData(moduleSide + "Motor 1 Encoder", motor1.getCurrentPosition());
        robot.telemetry.addData(moduleSide + "Motor 2 Encoder", motor2.getCurrentPosition());
        double rawAngle = (double)(motor2.getCurrentPosition() + motor1.getCurrentPosition())/2.0 * DEGREES_PER_TICK; //motor2-motor1 makes ccw positive (?)
        return new Angle(rawAngle, Angle.AngleType.ZERO_TO_360_HEADING);
    }


    //TRACKING METHODS
    //used for straight line distance tracking

    //new position tracking
    public Vector2d updatePositionTracking (Telemetry telemetry) {
        double newM1Encoder = motor1.getCurrentPosition();
        double newM2Encoder = motor2.getCurrentPosition();

        //angles are in radians
        double startingAngle = Math.toRadians((lastM1Encoder + lastM2Encoder)/2.0 * DEGREES_PER_TICK);
        double finalAngle = Math.toRadians((newM1Encoder + newM2Encoder)/2.0 * DEGREES_PER_TICK);
        double angleChange = (finalAngle - startingAngle);
        double averageAngle = (startingAngle + finalAngle)/2.0;

        telemetry.addData("Starting angle: ", startingAngle);
        telemetry.addData("Final angle: ", finalAngle);
        telemetry.addData("Starting angle radians: ", Math.toRadians(startingAngle));
        telemetry.addData("Final angle radians: ", Math.toRadians(finalAngle));
        telemetry.addData("Angle change: ", angleChange);

//        //positions in cm
//        double startingPosition = (lastM1Encoder - lastM2Encoder)/2.0  * CM_PER_TICK;
//        double finalPosition = (newM1Encoder - newM2Encoder)/2.0 * CM_PER_TICK;
//        double positionChange = finalPosition - startingPosition;
//        if (reversed) {
//            positionChange *= -1;
//        }

        double motor1Change = newM1Encoder - lastM1Encoder;
        double motor2Change = newM2Encoder - lastM2Encoder;
        double positionChange;

        //if module is reversed, subtract distance traveled instead of adding
        //module is driving in the opposite direction that the encoders "think" it is
        if (reversed) {
            positionChange = -(motor1Change - motor2Change)/2.0 * CM_PER_TICK;
        } else {
            positionChange = (motor1Change - motor2Change)/2.0 * CM_PER_TICK;
        }

//        telemetry.addData("Starting position: ", startingPosition);
//        telemetry.addData("Final position: ", finalPosition);
        telemetry.addData("Position change: ", positionChange);

        Vector2d displacementVec;
        //derivation documented elsewhere
        if (angleChange != 0) {
            //circular arc approximation
            //double deltaYPos = positionChange/angleChange * (Math.sin(Math.toRadians(startingAngle)) - Math.sin(Math.toRadians(finalAngle)));
            //double deltaXPos = positionChange/angleChange * (Math.cos(Math.toRadians(finalAngle)) - Math.cos(Math.toRadians(startingAngle)));
            //straight line approximation
            double deltaXPos = Math.sin(averageAngle) * positionChange;
            double deltaYPos = Math.cos(averageAngle) * positionChange;
            displacementVec = new Vector2d(deltaXPos, deltaYPos);
            telemetry.addData("Doing the fancy way ", displacementVec.getX());
        } else if (positionChange == 0) {
            telemetry.addData("Nothing is moving ", "");
            displacementVec = new Vector2d(0, 0);
        } else {
            displacementVec = new Vector2d(new Angle(startingAngle, Angle.AngleType.ZERO_TO_360_HEADING));
            displacementVec.normalize(positionChange);
            telemetry.addData("Doing the straight line way ","");
        }

        telemetry.addData("Delta X Pos: ", displacementVec.getX());
        telemetry.addData("Delta Y Pos: ", displacementVec.getY()); //was printing the final position instead...
        //telemetry.addData("Position change/angle change: ",positionChange/angleChange);

        lastM1Encoder = newM1Encoder;
        lastM2Encoder = newM2Encoder;

        return displacementVec;
    }

    public void updateTracking () {
        //important to set these to a variable so getCurrentPosition() is not called multiple times in single cycle
        double currentMotor1Encoder = motor1.getCurrentPosition();
        double currentMotor2Encoder = motor2.getCurrentPosition();

        double motor1Change = currentMotor1Encoder - lastMotor1Encoder;
        double motor2Change = currentMotor2Encoder - lastMotor2Encoder;

        //if module is reversed, subtract distance traveled instead of adding
        //module is driving in the opposite direction that the encoders "think" it is
        if (reversed) {
            distanceTraveled -= (motor1Change - motor2Change)/2.0 * CM_PER_TICK;
        } else {
            distanceTraveled += (motor1Change - motor2Change)/2.0 * CM_PER_TICK;
        }

        lastMotor1Encoder = currentMotor1Encoder;
        lastMotor2Encoder = currentMotor2Encoder;

        robot.telemetry.addData(moduleSide + "Motor 1 Encoder", currentMotor1Encoder);
        robot.telemetry.addData(moduleSide + "Motor 2 Encoder", currentMotor2Encoder);
        robot.telemetry.update();
    }

    public void resetDistanceTraveled () {
        distanceTraveled = 0;
        lastMotor1Encoder = motor1.getCurrentPosition();
        lastMotor2Encoder = motor2.getCurrentPosition();
    }

    //returns distance (in cm) traveled since distance was last reset
    public double getDistanceTraveled () {
        return distanceTraveled;
    }
}
