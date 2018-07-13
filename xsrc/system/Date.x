/**
 * The Date value type is used to represent the information about a date in Gregorian form.
 */
const Date(Int year, Int month, Int day)
        implements Sequential
    {
    @RO Int dayOfYear;
    enum DayOfWeek {Sunday, Monday, Tuesday, Wednesday, Thursday, Friday, Saturday}

    // ops
    @Op Date add(Duration duration)
        {
        TODO
        }

    @Op Duration sub(Date date)
        {
        TODO
        }


    @Op Date sub(Duration duration)
        {
        TODO
        }

    // ----- Sequential ----------------------------------------------------------------------------

    conditional Date prev()
        {
        TODO
        }

    conditional Date next()
        {
        TODO
        }

    // ----- conversions ---------------------------------------------------------------------------

    DateTime to<DateTime>()
        {
        return new DateTime(this, Time.MIDNIGHT);
        }
    }
