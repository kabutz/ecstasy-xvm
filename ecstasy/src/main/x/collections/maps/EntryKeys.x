/**
 * An implementation of the Set for the [Map.keys] property that delegates back to the map and
 * to the map's [Map.entries] set.
 */
class EntryKeys<Key, Value>(Map<Key, Value> contents)
        implements Set<Key>
    {
    public/private Map<Key, Value> contents;

    @Override
    Int size.get()
        {
        return contents.size;
        }

    @Override
    Boolean empty.get()
        {
        return contents.empty;
        }

    @Override
    Iterator<Key> iterator()
        {
        return new Iterator()
            {
            Iterator<Map<Key, Value>.Entry> entryIterator = contents.entries.iterator();

            @Override
            conditional Key next()
                {
                if (Map<Key, Value>.Entry entry := entryIterator.next())
                    {
                    return True, entry.key;
                    }

                return False;
                }
            };
        }

    @Override
    EntryKeys remove(Key key)
        {
        verifyMutable();
        contents.remove(key);
        return this;
        }

    @Override
    (EntryKeys, Int) removeAll(function Boolean (Key) shouldRemove)
        {
        verifyMutable();

        (_, Int removed) = contents.entries.removeAll(entry -> shouldRemove(entry.key));
        return this, removed;
        }

    @Override
    EntryKeys clear()
        {
        verifyMutable();
        contents.clear();
        return this;
        }

    /**
     * Some operations require that the containing Map be mutable; this method throws an exception
     * if the Map is not mutable.
     *
     * @return True
     *
     * @throws ReadOnly if the Map is not mutable
     */
    protected Boolean verifyMutable()
        {
        if (!contents.inPlace)
            {
            throw new ReadOnly("Map operation requires inPlace == True");
            }
        return True;
        }
    }
